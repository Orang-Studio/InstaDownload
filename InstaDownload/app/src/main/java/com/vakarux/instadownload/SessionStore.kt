package com.vakarux.instadownload

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class IgSession(
    val sessionId: String,
    val csrfToken: String?,
    val userId: String?,
    val cookieHeader: String? = null,
    val userAgent: String? = null,
)

class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { openPrefs() }

    val session: IgSession?
        get() = prefs.getString(KEY_SESSION_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                IgSession(
                    sessionId = it,
                    csrfToken = prefs.getString(KEY_CSRF_TOKEN, null),
                    userId = prefs.getString(KEY_USER_ID, null),
                    cookieHeader = prefs.getString(KEY_COOKIE_HEADER, null),
                    userAgent = prefs.getString(KEY_USER_AGENT, null),
                )
            }

    val isLoggedIn: Boolean
        get() = session != null

    fun save(session: IgSession) {
        prefs.edit()
            .putString(KEY_SESSION_ID, session.sessionId)
            .putString(KEY_CSRF_TOKEN, session.csrfToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_COOKIE_HEADER, session.cookieHeader)
            .putString(KEY_USER_AGENT, session.userAgent)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun openPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return runCatching {
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // A corrupt keystore entry should not make the rest of the app unusable.
            // Clearing this app-owned store also makes the user sign in again instead
            // of falling back to plaintext session storage.
            appContext.deleteSharedPreferences(PREFS_NAME)
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "instagram_session"
        const val KEY_SESSION_ID = "sessionid"
        const val KEY_CSRF_TOKEN = "csrftoken"
        const val KEY_USER_ID = "ds_user_id"
        const val KEY_COOKIE_HEADER = "cookie_header"
        const val KEY_USER_AGENT = "user_agent"
    }
}
