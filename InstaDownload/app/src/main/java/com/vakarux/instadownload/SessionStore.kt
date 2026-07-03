package com.vakarux.instadownload

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class IgSession(val sessionId: String, val csrfToken: String?, val userId: String?)

class SessionStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ig_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val sessionId: String?
        get() = prefs.getString(KEY_SESSIONID, null)

    val csrfToken: String?
        get() = prefs.getString(KEY_CSRFTOKEN, null)

    val userId: String?
        get() = prefs.getString(KEY_DS_USER_ID, null)

    val isLoggedIn: Boolean
        get() = !sessionId.isNullOrEmpty()

    val session: IgSession?
        get() = sessionId?.takeIf { it.isNotEmpty() }?.let { IgSession(it, csrfToken, userId) }

    fun save(sessionId: String, csrfToken: String?, userId: String?) {
        prefs.edit()
            .putString(KEY_SESSIONID, sessionId)
            .putString(KEY_CSRFTOKEN, csrfToken)
            .putString(KEY_DS_USER_ID, userId)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_SESSIONID = "sessionid"
        const val KEY_CSRFTOKEN = "csrftoken"
        const val KEY_DS_USER_ID = "ds_user_id"
    }
}
