package com.vakarux.instadownload

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.abs

data class PostMeta(
    val username: String?,
    val caption: String?,
    val url: String,
    val song: String?,
    val takenAtSec: Long,
)

data class MediaResult(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val reduced: Boolean = false,
    val baseName: String = "",
    val meta: PostMeta? = null,
) {
    val previewUrl: String? get() = thumbnailUrl ?: url.takeIf { !isVideo }
}

enum class PostType { PHOTO, VIDEO, CAROUSEL }

data class ProfilePost(
    val code: String,
    val type: PostType,
    val takenAtSec: Long,
    val caption: String,
    val previewUrl: String?,
    val mediaCount: Int,
)

class ProfilePage(val posts: List<ProfilePost>, val nextCursor: String?, val profilePicUrl: String?)

private class StaleQueryException(message: String) : Exception(message)

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
    private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:157.0) Gecko/20100101 Firefox/157.0"
    private const val IG_APP_ID = "936619743392459"
    private const val PROFILE_QUERY = "PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery_connection"
    private const val IG_EPOCH_MS = 1314220021721L

    private var lsd: String? = null
    private val profileIds = mutableMapOf<String, String>()
    private val profilePics = mutableMapOf<String, String>()

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
            durationSec = item.optDouble("video_duration", 0.0).takeIf { it > 0 }
                ?: dashDuration(item.optString("video_dash_manifest")),
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

        return MediaResult(
            picUrl, isVideo = false, baseName = "${username}_profile",
            meta = PostMeta(username, null, "https://www.instagram.com/$username/", null, 0L)
        )
    }

    fun extractProfileUsername(url: String): String? {
        val m = PROFILE_REGEX.matcher(url.trim())
        return if (m.matches()) m.group(1)?.takeUnless { it.lowercase() in RESERVED_PROFILE_PATHS } else null
    }

    fun isProfileUrl(url: String): Boolean = extractProfileUsername(url) != null

    fun fetchProfilePage(username: String, cursor: String?, settings: AppSettings): ProfilePage {
        val html = if (cursor == null || lsd == null || username !in profileIds) profileHtml(username) else null
        settings.profileQuery?.let { cached ->
            try {
                return queryProfile(username, cursor, cached)
            } catch (e: StaleQueryException) {
                settings.profileQuery = null
            }
        }
        val fresh = discoverProfileQuery(html ?: profileHtml(username))
        settings.profileQuery = fresh
        return try {
            queryProfile(username, cursor, fresh)
        } catch (e: StaleQueryException) {
            throw Exception(
                "Could not load posts for @$username. The account may be private or not exist.\n\n${e.message}"
            )
        }
    }

    private fun profileHtml(username: String): String {
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/$username/")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .get().build()
        ).execute()
        val html = response.body?.string()
            ?: throw Exception("Profile HTTP ${response.code}: empty body")
        if (!response.isSuccessful) throw Exception("Profile HTTP ${response.code}")
        lsd = Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw Exception("Profile page: no LSD token")
        Regex(""""profile_pic_url":"([^"]+)"""").find(html)?.groupValues?.get(1)?.let {
            profilePics[username] = JSONObject("{\"u\":\"$it\"}").getString("u")
        }
        profileIds[username] = Regex(""""all_media_count":[^,]*,"id":"(\d+)"""").find(html)?.groupValues?.get(1)
            ?: throw Exception("Could not find @$username. The account may not exist.")
        return html
    }

    private fun discoverProfileQuery(html: String): String {
        var docId: String? = null
        var providedVariables: List<String>? = null
        val docRegex = Regex(""""${PROFILE_QUERY}_instagramRelayOperation",\[\],\(function\([^)]*\)\{\w+\.exports="(\d+)"""")
        val varsRegex = Regex("""name:"$PROFILE_QUERY",operationKind:"query",text:null,providedVariables:\{([^}]*)\}""")
        Regex("""https:\\?/\\?/static\.cdninstagram\.com\\?/rsrc\.php\\?/[^"]+?\.js[^"]*""").findAll(html)
            .map { it.value.replace("\\/", "/") }.distinct().toList().asReversed()
            .forEach { jsUrl ->
                if (docId != null && providedVariables != null) return@forEach
                val js = runCatching {
                    client.newCall(Request.Builder().url(jsUrl).header("User-Agent", DESKTOP_UA).get().build())
                        .execute().use { it.body?.string() }
                }.getOrNull() ?: return@forEach
                docId = docId ?: docRegex.find(js)?.groupValues?.get(1)
                providedVariables = providedVariables ?: varsRegex.find(js)?.groupValues?.get(1)
                    ?.let { Regex("__relay_internal__pv__\\w+").findAll(it).map { m -> m.value }.distinct().toList() }
            }
        return "${docId ?: throw Exception("Could not find Instagram's profile posts query")}\n" +
            providedVariables.orEmpty().joinToString(",")
    }

    private fun queryProfile(username: String, cursor: String?, query: String): ProfilePage {
        val docId = query.substringBefore('\n')
        val variables = JSONObject()
            .put("after", cursor ?: JSONObject.NULL)
            .put("first", 12)
            .put("id", profileIds.getValue(username))
        query.substringAfter('\n').split(',').filter { it.isNotBlank() }.forEach { variables.put(it, false) }
        val csrf = cookieStore["www.instagram.com"]?.firstOrNull { it.name == "csrftoken" }?.value.orEmpty()
        val response = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/api/graphql")
                .header("User-Agent", DESKTOP_UA)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-CSRFToken", csrf)
                .header("X-FB-LSD", lsd.orEmpty())
                .header("X-FB-Friendly-Name", PROFILE_QUERY)
                .header("X-ASBD-ID", "359341")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", "https://www.instagram.com/$username")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .post(
                    FormBody.Builder()
                        .add("av", "0")
                        .add("__user", "0")
                        .add("__a", "1")
                        .add("lsd", lsd.orEmpty())
                        .add("fb_api_caller_class", "RelayModern")
                        .add("fb_api_req_friendly_name", PROFILE_QUERY)
                        .add("server_timestamps", "true")
                        .add("variables", variables.toString())
                        .add("doc_id", docId)
                        .build()
                ).build()
        ).execute()
        val text = response.use { it.body?.string().orEmpty() }
        if (response.code == 429) throw Exception(
            "Instagram is limiting requests right now. Wait a few minutes, then tap Retry.\n\n" +
                "Profile query HTTP ${response.code}: ${text.take(200)}"
        )
        val connection = runCatching { JSONObject(text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("node")?.optJSONObject("polaris_ordered_timeline_connection")
            ?: throw StaleQueryException("Profile query HTTP ${response.code}: ${text.take(200)}")

        val edges = connection.optJSONArray("edges") ?: JSONArray()
        val posts = (0 until edges.length()).mapNotNull { i ->
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: return@mapNotNull null
            val code = node.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProfilePost(
                code = code,
                type = when (node.optInt("media_type")) {
                    8 -> PostType.CAROUSEL
                    2 -> PostType.VIDEO
                    else -> PostType.PHOTO
                },
                takenAtSec = node.optString("pk").toLongOrNull()?.let { ((it shr 23) + IG_EPOCH_MS) / 1000 } ?: 0L,
                caption = node.optJSONObject("caption")?.optString("text").orEmpty(),
                previewUrl = node.optString("display_uri").takeIf { it.isNotBlank() },
                mediaCount = node.optInt("carousel_media_count").coerceAtLeast(1),
            )
        }
        val pageInfo = connection.optJSONObject("page_info")
        val next = pageInfo?.optString("end_cursor")
            ?.takeIf { pageInfo.optBoolean("has_next_page") && it.isNotBlank() && it != cursor }
        return ProfilePage(posts, next, profilePics[username])
    }

    private fun dashDuration(manifest: String): Double {
        val m = Regex("""mediaPresentationDuration="PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?"""").find(manifest)
            ?: return 0.0
        val (h, min, sec) = m.destructured
        return (h.toDoubleOrNull() ?: 0.0) * 3600 + (min.toDoubleOrNull() ?: 0.0) * 60 + (sec.toDoubleOrNull() ?: 0.0)
    }

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
            .map { extractProductMedia(it, targetWidth, postBaseName(it, shortcode), postMeta(it, shortcode)) }
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

    private fun postUsername(product: JSONObject): String? =
        (product.optJSONObject("user") ?: product.optJSONObject("owner"))
            ?.optString("username")?.takeIf { it.isNotBlank() }

    private fun postBaseName(product: JSONObject, shortcode: String): String {
        val desc = product.optJSONObject("caption")?.optString("text").orEmpty()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().take(30).trim().replace(' ', '_')
        return listOfNotNull(postUsername(product), desc.ifBlank { null }, if (desc.isBlank()) shortcode else shortcode.take(3))
            .joinToString("_")
    }

    private fun postMeta(product: JSONObject, shortcode: String): PostMeta {
        val clips = product.optJSONObject("clips_metadata")
        val licensed = clips?.optJSONObject("music_info")?.optJSONObject("music_asset_info")
            ?.let { listOf(it.optString("display_artist"), it.optString("title")) }
        val original = clips?.optJSONObject("original_sound_info")
            ?.let { listOf(it.optJSONObject("ig_artist")?.optString("username").orEmpty(), it.optString("original_audio_title")) }
        return PostMeta(
            username = postUsername(product),
            caption = product.optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() },
            url = "https://www.instagram.com/p/$shortcode/",
            song = (licensed ?: original)?.filter { it.isNotBlank() }?.joinToString(" - ")?.takeIf { it.isNotBlank() },
            takenAtSec = product.optLong("taken_at"),
        )
    }

    private fun extractProductMedia(product: JSONObject, targetWidth: Int, baseName: String, meta: PostMeta): List<MediaResult> {
        product.optJSONArray("carousel_media")?.let { carousel ->
            return (0 until carousel.length()).mapNotNull { i ->
                carousel.optJSONObject(i)?.let { extractSingleStoryItem(it, targetWidth) }
                    ?.copy(baseName = "${baseName}_${i + 1}", meta = meta)
            }
        }
        return listOfNotNull(extractSingleStoryItem(product, targetWidth)?.copy(baseName = baseName, meta = meta))
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
