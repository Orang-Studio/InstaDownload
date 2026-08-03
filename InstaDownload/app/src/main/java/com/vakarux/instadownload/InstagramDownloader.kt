package com.vakarux.instadownload

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class MediaResult(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String? = null,
) {
    val previewUrl: String? get() = thumbnailUrl ?: url.takeIf { !isVideo }
}

object InstagramDownloader {

    private val SHORTCODE_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)"
    )
    private val STORY_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/stories/([A-Za-z0-9._]+)/([0-9]+)"
    )

    private const val SHORTCODE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private data class StoryRequest(val username: String, val mediaId: String)

    // Simple in-memory cookie jar so cookies persist across requests in one session
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                removeAll { c -> cookies.any { it.name == c.name } }
                addAll(cookies)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host] ?: emptyList()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .build()

    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    private val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun getMediaItems(
        postUrl: String,
        session: IgSession? = null,
        quality: DownloadQuality = DownloadQuality.BEST
    ): List<MediaResult> {
        extractStory(postUrl)?.let { story ->
            val s = session ?: throw UnsupportedOperationException(
                "Stories are login-only. Tap Log in with Instagram, then try again."
            )
            return tryMediaInfoApi(story.mediaId, s, quality)
        }

        val shortcode = extractShortcode(postUrl)
            ?: throw IllegalArgumentException("Invalid Instagram URL: $postUrl")

        val embedError: String
        try {
            return tryEmbedPage(shortcode)
        } catch (e: Exception) {
            embedError = e.message ?: e.javaClass.simpleName
        }

        if (session != null) {
            try {
                return tryMediaInfoApi(shortcodeToMediaId(shortcode), session, quality)
            } catch (e: Exception) {
                throw Exception(
                    "Could not fetch this post.\n\n" +
                        "Embed: $embedError\n" +
                        "Logged-in API: ${e.message}"
                )
            }
        }

        throw Exception(
            "Could not fetch this post — it may be private, age-restricted, or deleted.\n" +
                "Log in from the app to download content only visible to your account.\n\n" +
                "Embed: $embedError"
        )
    }

    private fun tryMediaInfoApi(
        mediaId: String, session: IgSession, quality: DownloadQuality
    ): List<MediaResult> {
        val cookie = listOfNotNull(
            "sessionid=${session.sessionId}",
            session.csrfToken?.let { "csrftoken=$it" },
            session.userId?.let { "ds_user_id=$it" },
        ).joinToString("; ")

        val resp = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/api/v1/media/$mediaId/info/")
                .header("User-Agent", DESKTOP_UA)
                .header("X-IG-App-ID", "936619743392459")
                .header("Cookie", cookie)
                .get().build()
        ).execute()

        val body = resp.body?.string().orEmpty()
        if (body.trimStart().startsWith('<'))
            throw Exception("Media info HTTP ${resp.code}: session rejected — log in again")

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw Exception("Media info HTTP ${resp.code}: bad JSON — ${body.take(150)}")
        val item = json.optJSONArray("items")?.optJSONObject(0)
            ?: throw Exception("Media info HTTP ${resp.code}: ${json.optString("message").ifBlank { "no media returned" }}")

        item.optJSONArray("carousel_media")?.let { slides ->
            val items = (0 until slides.length()).mapNotNull {
                slides.optJSONObject(it)?.let { node -> extractNode(node, quality) }
            }
            if (items.isNotEmpty()) return items
        }
        return extractNode(item, quality)?.let { listOf(it) }
            ?: throw Exception("Media info: no downloadable media")
    }

    private fun extractNode(item: JSONObject, quality: DownloadQuality): MediaResult? {
        val images = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
        val imageIndex = if (quality == DownloadQuality.DATA_SAVER) (images?.length() ?: 1) - 1 else 0
        val poster = images?.optJSONObject(imageIndex.coerceAtLeast(0))
            ?.optString("url")?.takeIf { it.isNotBlank() }
        val videos = item.optJSONArray("video_versions")
        val videoIndex = if (quality == DownloadQuality.DATA_SAVER) (videos?.length() ?: 1) - 1 else 0
        videos?.optJSONObject(videoIndex.coerceAtLeast(0))
            ?.optString("url")?.takeIf { it.isNotBlank() }
            ?.let { return MediaResult(it, isVideo = true, thumbnailUrl = poster) }
        return poster?.let { MediaResult(it, isVideo = false, thumbnailUrl = it) }
    }

    private fun tryEmbedPage(shortcode: String): List<MediaResult> {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/p/$shortcode/embed/captioned/")
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Referer", "https://www.instagram.com/")
                .get().build()
        ).execute()

        val html = response.body?.string()
            ?: throw Exception("Embed HTTP ${response.code}: empty body")
        if (!response.isSuccessful)
            throw Exception("Embed HTTP ${response.code}: ${html.take(120)}")

        // Instagram double-encodes JSON inside the embed page:
        // keys/values are delimited by \" and path separators are \\\/
        fun String.unescape() = replace("\\\\\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .let { s ->
                // Only run URLDecoder.decode if contains %XX pattern
                // safeguard against stray non-escape '%' url patterns
                if (s.contains(Regex("%[0-9A-Fa-f]{2}")))
                    runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
                else s
            }

        // Single video
        Regex("""\\"video_url\\":\\"(https:(?:(?!\\").)*)""").find(html)?.let {
            val poster = Regex("""\\"display_url\\":\\"(https:(?:(?!\\").)*)""")
                .find(html)?.groupValues?.get(1)?.unescape()
            return listOf(MediaResult(it.groupValues[1].unescape(), isVideo = true, thumbnailUrl = poster))
        }

        if (Regex("""\\"is_video\\":true""").containsMatchIn(html)) {
            throw Exception("Embed HTTP ${response.code}: is_video=true but video_url missing from embed response")
        }

        // Single image or carousel (collect all unique display_url entries)
        val imagesFromDisplayUrlJson = Regex("""\\"display_url\\":\\"(https:(?:(?!\\").)*)""")
            .findAll(html)
            .map { MediaResult(it.groupValues[1].unescape(), isVideo = false) }
            .distinctBy { it.url }
            .toList()
        if (imagesFromDisplayUrlJson.isNotEmpty()) return imagesFromDisplayUrlJson

        // Fallback: image tag with EmbeddedMediaImage class
        val imagesFromImgEmbeddedMediaImageClass = Regex("""<img[^>]*class="EmbeddedMediaImage"[^>]*src="(https:[^"]*)"""")
            .findAll(html)
            .map { MediaResult(it.groupValues[1].unescape(), isVideo = false) }
            .distinctBy { it.url }
            .toList()
        if (imagesFromImgEmbeddedMediaImageClass.isNotEmpty()) return imagesFromImgEmbeddedMediaImageClass

        // Fallback: OG image tag (plain HTML attribute, no double-encoding)
        Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.let {
            return listOf(MediaResult(it.groupValues[1].unescape(), isVideo = false))
        }

        throw Exception("Embed HTTP ${response.code}: no media URL found (${html.length} chars)")
    }

    private fun mediaRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", MOBILE_UA)
        .header("Referer", "https://www.instagram.com/")
        .get().build()

    fun downloadToStream(url: String, out: java.io.OutputStream) {
        val response = client.newCall(mediaRequest(url)).execute()
        if (!response.isSuccessful) throw Exception("Download HTTP ${response.code}")
        response.body?.byteStream()?.copyTo(out)
            ?: throw Exception("Empty download body")
    }

    fun fetchBytes(url: String): ByteArray {
        val response = client.newCall(mediaRequest(url)).execute()
        if (!response.isSuccessful) throw Exception("Preview HTTP ${response.code}")
        return response.body?.bytes() ?: throw Exception("Empty preview body")
    }

    private fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url)
        return if (m.find()) m.group(1)!!.take(11) else null
    }

    private fun shortcodeToMediaId(shortcode: String): String =
        shortcode.fold(BigInteger.ZERO) { id, c ->
            id * BigInteger.valueOf(64) + BigInteger.valueOf(SHORTCODE_ALPHABET.indexOf(c).toLong())
        }.toString()

    private fun extractStory(url: String): StoryRequest? {
        val m = STORY_REGEX.matcher(url)
        return if (m.find()) StoryRequest(m.group(1)!!, m.group(2)!!) else null
    }
}
