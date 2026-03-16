package com.vakarux.instadownload

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class MediaResult(val url: String, val isVideo: Boolean)

object InstagramDownloader {

    private val SHORTCODE_REGEX = Pattern.compile(
        "instagram\\.com/(?:reel|p|tv)/([A-Za-z0-9_-]+)"
    )

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

    fun getMediaUrl(postUrl: String): MediaResult {
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
            "Both strategies failed.\n" +
            "Embed: $embedError\n" +
            "GraphQL: $graphqlError"
        )
    }

    // ── Strategy 1: embed page ──────────────────────────────────────────────
    private fun tryEmbedPage(shortcode: String): MediaResult {
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

        fun String.unescape() = replace("\\/", "/").replace("\\u0026", "&")

        Regex(""""video_url":"(https://[^"]+)"""").find(html)?.let {
            return MediaResult(it.groupValues[1].unescape(), isVideo = true)
        }
        Regex(""""display_url":"(https://[^"]+)"""").find(html)?.let {
            return MediaResult(it.groupValues[1].unescape(), isVideo = false)
        }
        Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.let {
            return MediaResult(it.groupValues[1].unescape(), isVideo = false)
        }

        throw Exception("Embed HTTP ${response.code}: no media URL found (${html.length} chars)")
    }

    // ── Strategy 2: GraphQL with proper session ─────────────────────────────
    // Instagram's web GraphQL requires:
    //   1. Cookies from the main page (csrftoken etc.)
    //   2. fb_dtsg anti-CSRF token extracted from the reel page HTML
    //   3. Those cookies forwarded in the POST
    private fun tryGraphQL(shortcode: String): MediaResult {
        // Step 1: warm up session cookies
        client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/")
                .header("User-Agent", DESKTOP_UA)
                .get().build()
        ).execute().close()

        // Step 2: visit reel page — sets more cookies and embeds fb_dtsg
        val reelPage = client.newCall(
            Request.Builder()
                .url("https://www.instagram.com/reel/$shortcode/")
                .header("User-Agent", DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.8")
                .header("Referer", "https://www.instagram.com/")
                .get().build()
        ).execute()
        val reelHtml = reelPage.body?.string() ?: ""
        reelPage.close()

        // Step 3: extract fb_dtsg (Relay/React anti-CSRF token)
        val fbDtsg = Regex(""""token":"(AdQ[^"]+)"""").find(reelHtml)?.groupValues?.get(1)
            ?: Regex(""""fb_dtsg","[^"]*","[^"]*","([^"]+)"""").find(reelHtml)?.groupValues?.get(1)
            ?: Regex(""""DTSGInitData"[^}]+"token":"([^"]+)"""").find(reelHtml)?.groupValues?.get(1)

        val csrfToken = cookieStore["www.instagram.com"]
            ?.firstOrNull { it.name == "csrftoken" }?.value ?: ""

        // Step 4: POST to graphql with session cookies + fb_dtsg
        val body = FormBody.Builder()
            .addEncoded("variables", """{"shortcode":"$shortcode"}""")
            .add("doc_id", "8845758582119845")
            .add("server_timestamps", "true")
            .apply { if (fbDtsg != null) add("fb_dtsg", fbDtsg) }
            .build()

        val request = Request.Builder()
            .url("https://www.instagram.com/graphql/query")
            .post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Referer", "https://www.instagram.com/reel/$shortcode/")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-CSRFToken", csrfToken)
            .header("X-FB-Friendly-Name", "PolarisPostRootQuery")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val resp = client.newCall(request).execute()
        val respBody = resp.body?.string()
            ?: throw Exception("GraphQL HTTP ${resp.code}: empty body (fb_dtsg=${fbDtsg?.take(10)}, csrf=${csrfToken.take(10)})")
        if (!resp.isSuccessful)
            throw Exception("GraphQL HTTP ${resp.code}: ${respBody.take(200)} (fb_dtsg=${fbDtsg?.take(10)})")

        val media = try {
            JSONObject(respBody).getJSONObject("data")
                .optJSONObject("xdt_shortcode_media")
        } catch (e: Exception) {
            throw Exception("GraphQL HTTP ${resp.code}: JSON parse failed — ${respBody.take(150)}")
        } ?: throw Exception("GraphQL HTTP ${resp.code}: xdt_shortcode_media=null — ${respBody.take(150)}")

        return if (media.optBoolean("is_video", false)) {
            val url = media.optString("video_url").takeIf { it.isNotEmpty() }
                ?: throw Exception("GraphQL: is_video=true but video_url empty")
            MediaResult(url, isVideo = true)
        } else {
            val url = media.optString("display_url").takeIf { it.isNotEmpty() }
                ?: throw Exception("GraphQL: display_url empty")
            MediaResult(url, isVideo = false)
        }
    }

    private fun extractShortcode(url: String): String? {
        val m = SHORTCODE_REGEX.matcher(url)
        return if (m.find()) m.group(1) else null
    }
}
