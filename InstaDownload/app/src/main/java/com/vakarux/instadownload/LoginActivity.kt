package com.vakarux.instadownload

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import com.vakarux.instadownload.ui.AppIcons

class LoginActivity : ComponentActivity() {
    private lateinit var sessionStore: SessionStore
    private lateinit var webView: WebView

    @OptIn(ExperimentalMaterial3Api::class)
    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = SessionStore(this)

        val cookies = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    captureSessionIfPresent()
                }
            }
        }

        val theme = AppSettings(this).theme
        val topBar = ComposeView(this).apply {
            setContent {
                val darkTheme = when (theme) {
                    AppTheme.SYSTEM -> isSystemInDarkTheme()
                    AppTheme.LIGHT -> false
                    AppTheme.DARK -> true
                }
                InstaDownloadTheme(darkTheme = darkTheme) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.login_title)) },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    AppIcons.Close,
                                    contentDescription = stringResource(R.string.cancel_label),
                                )
                            }
                        },
                    )
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar)
            addView(webView)
        })

        // Loading after the cookie wipe prevents a stale WebView session from
        // being mistaken for the account the user just chose.
        cookies.removeAllCookies {
            cookies.flush()
            webView.loadUrl("https://www.instagram.com/accounts/login/")
        }
    }

    private fun captureSessionIfPresent() {
        val cookieManager = CookieManager.getInstance()
        val cookies = listOf(
            cookieManager.getCookie("https://www.instagram.com").orEmpty(),
            cookieManager.getCookie("https://instagram.com").orEmpty(),
            cookieManager.getCookie("https://i.instagram.com").orEmpty(),
        ).flatMap { it.split(';') }
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null
                else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
            }
            .toMap()

        val sessionId = cookies["sessionid"] ?: return
        sessionStore.save(
            IgSession(
                sessionId = sessionId,
                csrfToken = cookies["csrftoken"],
                userId = cookies["ds_user_id"],
                cookieHeader = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" },
                userAgent = webView.settings.userAgentString,
            )
        )
        cookieManager.flush()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
