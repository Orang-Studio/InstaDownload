package com.vakarux.instadownload

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object InstagramDownloader {

    private val SHORTCODE_REGEX = Pattern.compile(
        "instagram\\.com/(?:reel|p|tv)/([A-Za-z0-9_-]+)"
    )

    // doc_id for xdt_shortcode_media GraphQL query (used by instaloader 4.14+)
    private const val DOC_ID = "8845758582119845"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the direct video URL for the given Instagram post/reel URL,
     * or null if the post has no video.
     * Throws an exception on network or parse errors.
     */
    fun getVideoUrl(postUrl: String): String? {
        val shortcode = extractShortcode(postUrl)
            ?: throw IllegalArgumentException("Invalid Instagram URL: $postUrl")

        val variables = """{"shortcode":"$shortcode"}"""

        val request = Request.Builder()
            .url(
                "https://www.instagram.com/graphql/query" +
                "?variables=${variables.urlEncode()}" +
                "&doc_id=$DOC_ID" +
                "&server_timestamps=true"
            )
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .header("User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Accept-Encoding", "gzip, deflate")
            .header("Referer", "https://www.instagram.com/")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
            ?: throw Exception("Empty response from Instagram (HTTP ${response.code})")

        if (!response.isSuccessful) {
            throw Exception("Instagram returned HTTP ${response.code}: ${body.take(200)}")
        }

        return parseVideoUrl(body)
    }

    private fun parseVideoUrl(json: String): String? {
        val root = JSONObject(json)
        val media = root
            .getJSONObject("data")
            .optJSONObject("xdt_shortcode_media")
            ?: return null  // post not found or not a video

        val isVideo = media.optBoolean("is_video", false)
        if (!isVideo) return null

        return media.optString("video_url").takeIf { it.isNotEmpty() }
    }

    private fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url)
        return if (m.find()) m.group(1) else null
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
