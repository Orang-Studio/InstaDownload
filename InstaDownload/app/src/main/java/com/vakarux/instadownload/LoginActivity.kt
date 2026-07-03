package com.vakarux.instadownload

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

class LoginActivity : ComponentActivity() {

    private lateinit var sessionStore: SessionStore

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = SessionStore(this)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            removeAllCookies(null)
            flush()
        }

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    captureSessionIfPresent()
                }
            }
            loadUrl("https://www.instagram.com/accounts/login/")
        }

        setContentView(webView)
    }

    private fun captureSessionIfPresent() {
        val cookies = CookieManager.getInstance()
            .getCookie("https://www.instagram.com") ?: return

        val map = cookies.split(";")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null
                else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }
            .toMap()

        val sessionId = map["sessionid"]
        if (!sessionId.isNullOrEmpty()) {
            sessionStore.save(
                sessionId = sessionId,
                csrfToken = map["csrftoken"],
                userId = map["ds_user_id"]
            )
            setResult(RESULT_OK)
            finish()
        }
    }
}
