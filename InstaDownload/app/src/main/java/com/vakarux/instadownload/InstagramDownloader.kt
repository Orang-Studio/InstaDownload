package com.vakarux.instadownload

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

data class MediaResult(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val reduced: Boolean = false,
) {
    val previewUrl: String? get() = thumbnailUrl ?: url.takeIf { !isVideo }
}

object InstagramDownloader {

    private val SHORTCODE_REGEX = Pattern.compile(
        "(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)"
    )
    private val PROFILE_REGEX = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/([A-Za-z0-9_.]+)/?(?:[?#].*)?$"
    )
    private val RESERVED_PROFILE_PATHS = setOf(
        "p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct",
        "about", "developer", "legal", "privacy", "graphql", "web", "download", "emails", "topics"
    )

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

    private val MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun getMediaItems(postUrl: String, targetWidth: Int = Int.MAX_VALUE): List<MediaResult> {
        val shortcode = extractShortcode(postUrl) ?: run {
            extractProfileUsername(postUrl)?.let { username ->
                return listOf(fetchProfilePicture(username))
            }
            throw IllegalArgumentException("Invalid Instagram URL: $postUrl")
        }

        try {
            return tryPostPage(shortcode, targetWidth)
        } catch (postFailure: Exception) {
            throw Exception(
                "Could not fetch this post. It may be private, age-restricted, or deleted. " +
                "This build only downloads public content — use the login build for private posts and stories.\n\n" +
                "Post page: ${postFailure.message ?: postFailure.javaClass.simpleName}"
            )
        }
    }

    private val SIZE_TOKEN = Regex("""_[ps](\d+)x(\d+)""")

    private fun JSONObject.renditionWidth(): Int = optInt("width").takeIf { it > 0 }
        ?: SIZE_TOKEN.find(optString("url"))?.groupValues?.get(1)?.toInt() ?: Int.MAX_VALUE

    private fun JSONObject.renditionHeight(): Int = optInt("height").takeIf { it > 0 }
        ?: SIZE_TOKEN.find(optString("url"))?.groupValues?.get(2)?.toInt() ?: 0

    private fun JSONArray?.pick(targetWidth: Int): JSONObject? =
        (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
            .filter { it.optString("url").isNotBlank() && !it.optString("url").contains(Regex("stp=c\\d")) }
            .minByOrNull { abs(it.renditionWidth() - targetWidth) }

    private fun extractSingleStoryItem(item: JSONObject, targetWidth: Int): MediaResult? {
        val images = item.optJSONObject("image_versions2")?.optJSONArray("candidates")
        val videos = item.optJSONArray("video_versions")
        val preview = images.pick(minOf(targetWidth, 640))?.optString("url")
            ?: item.optString("display_url").takeIf { it.isNotBlank() }

        android.util.Log.d("IGDBG", "keys=" + item.keys().asSequence().joinToString() + " vid0=" + videos?.optJSONObject(0) + " img0=" + images?.optJSONObject(0))
        val video = videos.pick(targetWidth)
        val chosen = video ?: images.pick(targetWidth)
            ?: return preview?.let { MediaResult(it, isVideo = false, thumbnailUrl = it) }
        val best = (if (video != null) videos else images).pick(Int.MAX_VALUE)?.renditionWidth() ?: 0
        val width = chosen.renditionWidth().takeIf { it < Int.MAX_VALUE } ?: item.optInt("original_width")
        val height = chosen.renditionHeight().takeIf { it > 0 } ?: item.optInt("original_height")
        return MediaResult(
            url = chosen.optString("url"),
            isVideo = video != null,
            thumbnailUrl = preview,
            width = width,
            height = height,
            durationSec = item.optDouble("video_duration", 0.0),
            reduced = chosen.renditionWidth() < best
        )
    }

    private fun fetchProfilePicture(username: String): MediaResult {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/$username/")
                .header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
                .get().build()
        ).execute()

        val html = response.body?.string()
            ?: throw Exception("Profile HTTP ${response.code}: empty body")
        if (!response.isSuccessful) throw Exception("Profile HTTP ${response.code}")

        val picUrl = Regex("""<meta property="og:image" content="([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: throw Exception("Could not find a profile picture for @$username — the account may not exist")

        return MediaResult(picUrl, isVideo = false)
    }

    private fun extractProfileUsername(url: String): String? {
        val m = PROFILE_REGEX.matcher(url.trim())
        return if (m.matches()) m.group(1)?.takeUnless { it.lowercase() in RESERVED_PROFILE_PATHS } else null
    }

    fun isProfileUrl(url: String): Boolean = extractProfileUsername(url) != null

    private fun tryPostPage(shortcode: String, targetWidth: Int): List<MediaResult> {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/p/$shortcode/")
                .header("User-Agent", "Googlebot/2.1 (+http://www.google.com/bot.html)")
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
            .map { extractProductMedia(it, targetWidth) }
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

    private fun extractProductMedia(product: JSONObject, targetWidth: Int): List<MediaResult> {
        product.optJSONArray("carousel_media")?.let { carousel ->
            return (0 until carousel.length()).mapNotNull { i ->
                carousel.optJSONObject(i)?.let { extractSingleStoryItem(it, targetWidth) }
            }
        }
        return listOfNotNull(extractSingleStoryItem(product, targetWidth))
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

    fun contentLength(url: String): Long =
        client.newCall(mediaRequest(url).newBuilder().head().build()).execute().use {
            it.header("Content-Length")?.toLongOrNull() ?: -1L
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

}
