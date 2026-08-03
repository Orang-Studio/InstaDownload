package com.vakarux.instadownload

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
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

    fun getMediaItems(postUrl: String): List<MediaResult> {
        extractStory(postUrl)?.let { story ->
            return tryPublicStory(story)
        }

        val shortcode = extractShortcode(postUrl)
            ?: throw IllegalArgumentException("Invalid Instagram URL: $postUrl")

        val embedFailure = runCatching { return tryEmbedPage(shortcode) }.exceptionOrNull()
        try {
            return tryPostPage(shortcode)
        } catch (postFailure: Exception) {
            throw Exception(
                "Could not fetch this post. It may be private, age-restricted, or deleted. " +
                "This build only downloads public content — use the login build for private posts and stories.\n\n" +
                "Embed: ${embedFailure?.message ?: "no media found"}\n" +
                "Post page: ${postFailure.message ?: postFailure.javaClass.simpleName}"
            )
        }
    }

    // ── Public story probe ─────────────────────────────────────────────────
    //
    // Public profile metadata is available anonymously, but normal story media
    // is only usable here if Instagram returns it from the public reels endpoint.
    // Current logged-out responses for normal user stories are usually empty.
    private fun tryPublicStory(story: StoryRequest): List<MediaResult> {
        val userId = fetchPublicUserId(story.username)
        val reelsJson = fetchPublicReelsMedia(userId, story.mediaId, story.username)
        val items = extractStoryMedia(reelsJson, userId, story.mediaId)
        if (items.isNotEmpty()) return items

        val reelCount = JSONObject(reelsJson).optJSONObject("reels")?.length() ?: 0
        throw UnsupportedOperationException(
            "Instagram did not expose this story through public anonymous endpoints. " +
                    "Resolved @${story.username} to user id $userId, but reels_media returned " +
                    "$reelCount reel(s). Normal stories require a logged-in session or may have expired."
        )
    }

    private fun fetchPublicUserId(username: String): String {
        val encodedUsername = URLEncoder.encode(username, "UTF-8")
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/api/v1/users/web_profile_info/?username=$encodedUsername")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.instagram.com/$username/")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-ASBD-ID", "129477")
                .header("X-Requested-With", "XMLHttpRequest")
                .get().build()
        ).execute()

        val body = response.body?.string()
            ?: throw Exception("Profile HTTP ${response.code}: empty body")
        if (!response.isSuccessful) {
            throw Exception("Profile HTTP ${response.code}: ${body.take(200)}")
        }

        val user = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("user")
            ?: throw Exception("Profile response did not include user data")

        if (user.optBoolean("is_private", false)) {
            throw UnsupportedOperationException("@$username is private")
        }

        return user.optString("id").takeIf { it.isNotBlank() }
            ?: throw Exception("Profile response did not include user id")
    }

    private fun fetchPublicReelsMedia(userId: String, mediaId: String, username: String): String {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$userId&media_id=$mediaId")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.instagram.com/stories/$username/$mediaId/")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-ASBD-ID", "129477")
                .header("X-Requested-With", "XMLHttpRequest")
                .get().build()
        ).execute()

        val body = response.body?.string()
            ?: throw Exception("Story HTTP ${response.code}: empty body")
        if (body.trimStart().startsWith('<')) {
            throw Exception("Story HTTP ${response.code}: got HTML instead of JSON")
        }
        if (!response.isSuccessful) {
            throw Exception("Story HTTP ${response.code}: ${body.take(200)}")
        }
        return body
    }

    private fun extractStoryMedia(
        reelsJson: String,
        userId: String,
        mediaId: String
    ): List<MediaResult> {
        val reels = JSONObject(reelsJson).optJSONObject("reels") ?: return emptyList()
        val reel = reels.optJSONObject(userId) ?: run {
            val keys = reels.keys()
            var found: JSONObject? = null
            while (keys.hasNext() && found == null) {
                found = reels.optJSONObject(keys.next())
            }
            found
        } ?: return emptyList()

        val storyItems = reel.optJSONArray("items") ?: return emptyList()
        for (i in 0 until storyItems.length()) {
            val item = storyItems.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val pk = item.optString("pk")
            if (id == mediaId || id.startsWith("${mediaId}_") || pk == mediaId) {
                return extractSingleStoryItem(item)?.let { listOf(it) } ?: emptyList()
            }
        }
        return emptyList()
    }

    private fun extractSingleStoryItem(item: JSONObject): MediaResult? {
        val poster = item.optJSONObject("image_versions2")
            ?.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?: item.optString("display_url").takeIf { it.isNotBlank() }

        item.optJSONArray("video_versions")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { return MediaResult(it, isVideo = true, thumbnailUrl = poster) }

        poster?.let { return MediaResult(it, isVideo = false, thumbnailUrl = it) }

        return null
    }

    private fun embedRefusalOrNull(html: String): String? {
        if (html.contains("Please wait a few minutes before you try again"))
            return "Instagram is rate-limiting this device. Wait a few minutes and try again."

        if (html.contains("\"contextJSON\":null"))
            return "Instagram would not serve this post to a logged-out client. " +
                    "That usually means it is age-restricted or login-gated, but it can also be " +
                    "private, deleted, or region-blocked — the embed page doesn't say which. " +
                    "This build is public-only; the login build can fetch it."

        if (!html.contains("\"contextJSON\":\"") && html.contains("/accounts/login/"))
            return "Instagram served a login wall. " +
                    "This build only downloads public content."

        return null
    }

    // ── Strategy 1: embed page ──────────────────────────────────────────────
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

        embedRefusalOrNull(html)?.let { throw UnsupportedOperationException(it) }

        // Instagram now double-encodes JSON inside the embed page:
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

    private fun tryPostPage(shortcode: String): List<MediaResult> {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/p/$shortcode/")
                .header("User-Agent", DESKTOP_UA)
                .get().build()
        ).execute()

        val html = response.body?.string()
            ?: throw Exception("Post HTTP ${response.code}: empty body")
        if (!response.isSuccessful) throw Exception("Post HTTP ${response.code}")

        val expectedMediaId = shortcodeToMediaId(shortcode)
        Regex("""<script\b[^>]*\bdata-sjs[^>]*>(\{.+?\})</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .mapNotNull { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
            .mapNotNull { findPublicProduct(it, expectedMediaId) }
            .map(::extractProductMedia)
            .firstOrNull { it.isNotEmpty() }
            ?.let { return it }
        throw Exception("Post HTTP ${response.code}: no public media found")
    }

    private fun findPublicProduct(value: Any?, expectedMediaId: String): JSONObject? {
        when (value) {
            is JSONObject -> {
                value.optJSONObject("if_not_gated_logged_out")?.let {
                    if (it.optString("pk") == expectedMediaId || it.optString("id") == expectedMediaId)
                        return it
                }
                if ((value.optString("pk") == expectedMediaId || value.optString("id") == expectedMediaId) &&
                    (value.has("video_versions") || value.has("carousel_media") || value.has("image_versions2")))
                    return value

                val keys = value.keys()
                while (keys.hasNext()) {
                    findPublicProduct(value.opt(keys.next()), expectedMediaId)?.let { return it }
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                findPublicProduct(value.opt(i), expectedMediaId)?.let { return it }
            }
        }
        return null
    }

    private fun extractProductMedia(product: JSONObject): List<MediaResult> {
        product.optJSONArray("carousel_media")?.let { carousel ->
            return (0 until carousel.length()).mapNotNull { i ->
                carousel.optJSONObject(i)?.let(::extractSingleStoryItem)
            }
        }
        return listOfNotNull(extractSingleStoryItem(product))
    }

    private fun shortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (character in shortcode) {
            val digit = alphabet.indexOf(character)
            require(digit >= 0) { "Invalid Instagram shortcode" }
            id = Math.addExact(Math.multiplyExact(id, 64L), digit.toLong())
        }
        return id.toString()
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

    private fun extractStory(url: String): StoryRequest? {
        val m = STORY_REGEX.matcher(url)
        return if (m.find()) StoryRequest(m.group(1), m.group(2)) else null
    }
}
