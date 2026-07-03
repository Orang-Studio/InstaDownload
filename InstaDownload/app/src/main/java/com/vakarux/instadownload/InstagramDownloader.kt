package com.vakarux.instadownload

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONObject
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
        "(?:instagram\\.com|instagr\\.am)/(?:reel|p|tv)/([A-Za-z0-9_-]+)"
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

    fun getMediaItems(postUrl: String, sessionId: String? = null): List<MediaResult> {
        extractStory(postUrl)?.let { story ->
            return tryPublicStory(story, sessionId)
        }

        val shortcode = extractShortcode(postUrl)
            ?: throw IllegalArgumentException("Invalid Instagram URL: $postUrl")

        val embedError: String
        try {
            return tryEmbedPage(shortcode)
        } catch (e: Exception) {
            embedError = e.message ?: e.javaClass.simpleName
        }

        val graphqlError: String
        try {
            return tryGraphQL(shortcode)
        } catch (e: Exception) {
            graphqlError = e.message ?: e.javaClass.simpleName
        }

        throw Exception(
            "Latest methods are patched, please open an issue\n\n" +
            "Embed: $embedError\n" +
            "GraphQL: $graphqlError"
        )
    }

    // ── Public story probe ─────────────────────────────────────────────────
    //
    // Public profile metadata is available anonymously, but normal story media
    // is only usable here if Instagram returns it from the public reels endpoint.
    // Current logged-out responses for normal user stories are usually empty.
    private fun tryPublicStory(story: StoryRequest, sessionId: String? = null): List<MediaResult> {
        val userId = fetchPublicUserId(story.username, sessionId)
        val reelsJson = fetchPublicReelsMedia(userId, story.mediaId, story.username, sessionId)
        val items = extractStoryMedia(reelsJson, userId, story.mediaId)
        if (items.isNotEmpty()) return items

        val reelCount = JSONObject(reelsJson).optJSONObject("reels")?.length() ?: 0
        throw UnsupportedOperationException(
            "Instagram did not expose this story. " +
                    "Resolved @${story.username} to user id $userId, but reels_media returned " +
                    "$reelCount reel(s). Log in from the app for stories, or the story may have expired."
        )
    }

    private fun fetchPublicUserId(username: String, sessionId: String? = null): String {
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
                .apply { sessionId?.let { header("Cookie", "sessionid=$it") } }
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

    private fun fetchPublicReelsMedia(userId: String, mediaId: String, username: String, sessionId: String? = null): String {
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
                .apply { sessionId?.let { header("Cookie", "sessionid=$it") } }
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

    private fun extractStoryMedia(reelsJson: String, userId: String, mediaId: String): List<MediaResult> {
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

        item.optJSONArray("video_versions")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { return MediaResult(it, isVideo = true, thumbnailUrl = poster) }

        poster?.let { return MediaResult(it, isVideo = false, thumbnailUrl = it) }

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

        // Instagram now double-encodes JSON inside the embed page:
        // keys/values are delimited by \" and path separators are \\\/
        fun String.unescape() = replace("\\\\\\/", "/").replace("\\u0026", "&")

        // Single video
        Regex("""\\"video_url\\":\\"(https:(?:(?!\\").)*)""").find(html)?.let {
            val poster = Regex("""\\"display_url\\":\\"(https:(?:(?!\\").)*)""")
                .find(html)?.groupValues?.get(1)?.unescape()
            return listOf(MediaResult(it.groupValues[1].unescape(), isVideo = true, thumbnailUrl = poster))
        }

        // Single image or carousel (collect all unique display_url entries)
        val images = Regex("""\\"display_url\\":\\"(https:(?:(?!\\").)*)""")
            .findAll(html)
            .map { MediaResult(it.groupValues[1].unescape(), isVideo = false) }
            .distinctBy { it.url }
            .toList()
        if (images.isNotEmpty()) return images

        // Fallback: OG image tag (plain HTML attribute, no double-encoding)
        Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.let {
            return listOf(MediaResult(it.groupValues[1].unescape(), isVideo = false))
        }

        throw Exception("Embed HTTP ${response.code}: no media URL found (${html.length} chars)")
    }

    // ── Strategy 2: GraphQL with session cookies ────────────────────────────
    // Requires:
    //   1. csrftoken cookie from homepage visit
    //   2. X-CSRFToken header matching that cookie
    //   3. X-IG-App-ID header
    // IMPORTANT: Do NOT visit the reel page before this call — doing so causes
    // Instagram to return an HTML soft-gate instead of JSON for subsequent
    // GraphQL requests on the same session.
    private fun tryGraphQL(shortcode: String): List<MediaResult> {
        // Step 1: warm up session — get csrftoken cookie
        client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get().build()
        ).execute().close()

        val csrfToken = cookieStore["www.instagram.com"]
            ?.firstOrNull { it.name == "csrftoken" }?.value ?: ""

        // Step 2: POST to graphql — cookies are forwarded automatically by cookieJar
        val body = FormBody.Builder()
            .add("variables", """{"shortcode":"$shortcode"}""")
            .add("doc_id", "8845758582119845")
            .build()

        val resp = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/graphql/query")
                .post(body)
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.instagram.com/")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-CSRFToken", csrfToken)
                .build()
        ).execute()

        val respBody = resp.body?.string()
            ?: throw Exception("GraphQL HTTP ${resp.code}: empty body")

        // Detect HTML before attempting JSON parse (indicates login gate or CSRF failure)
        if (respBody.trimStart().startsWith('<'))
            throw Exception("GraphQL HTTP ${resp.code}: got HTML instead of JSON — csrf=${csrfToken.take(8)}, preview=${respBody.take(80)}")

        if (!resp.isSuccessful)
            throw Exception("GraphQL HTTP ${resp.code}: ${respBody.take(200)}")

        val json = try {
            JSONObject(respBody)
        } catch (e: Exception) {
            throw Exception("GraphQL HTTP ${resp.code}: JSON parse failed — ${respBody.take(150)}")
        }
        // Surface API-level errors (e.g. stale doc_id returns data:null + errors array)
        if (json.isNull("data")) {
            val apiErr = json.optJSONArray("errors")
                ?.optJSONObject(0)?.optString("message", "unknown") ?: "data is null"
            throw Exception("GraphQL HTTP ${resp.code}: API error ($apiErr) — ${respBody.take(150)}")
        }
        val media = json.getJSONObject("data")
            .optJSONObject("xdt_shortcode_media")
            ?: throw Exception("GraphQL HTTP ${resp.code}: xdt_shortcode_media=null — ${respBody.take(150)}")

        // Carousel / sidecar post — return all slides
        val edges = media.optJSONObject("edge_sidecar_to_children")
            ?.optJSONArray("edges")
        if (edges != null && edges.length() > 0) {
            val items = mutableListOf<MediaResult>()
            for (i in 0 until edges.length()) {
                val node = edges.getJSONObject(i).getJSONObject("node")
                val poster = node.optString("display_url").takeIf { it.isNotEmpty() }
                if (node.optBoolean("is_video", false)) {
                    val url = node.optString("video_url").takeIf { it.isNotEmpty() } ?: continue
                    items += MediaResult(url, isVideo = true, thumbnailUrl = poster)
                } else {
                    val url = poster ?: continue
                    items += MediaResult(url, isVideo = false, thumbnailUrl = url)
                }
            }
            if (items.isNotEmpty()) return items
        }

        // Single photo or video
        val poster = media.optString("display_url").takeIf { it.isNotEmpty() }
        return if (media.optBoolean("is_video", false)) {
            val url = media.optString("video_url").takeIf { it.isNotEmpty() }
                ?: throw Exception("GraphQL: is_video=true but video_url empty")
            listOf(MediaResult(url, isVideo = true, thumbnailUrl = poster))
        } else {
            val url = poster ?: throw Exception("GraphQL: display_url empty")
            listOf(MediaResult(url, isVideo = false, thumbnailUrl = url))
        }
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
        return if (m.find()) m.group(1) else null
    }

    private fun extractStory(url: String): StoryRequest? {
        val m = STORY_REGEX.matcher(url)
        return if (m.find()) StoryRequest(m.group(1), m.group(2)) else null
    }
}
