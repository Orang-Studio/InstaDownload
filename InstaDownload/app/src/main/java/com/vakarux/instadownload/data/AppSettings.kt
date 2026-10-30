package com.vakarux.instadownload.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.vakarux.instadownload.model.DownloadQuality
import com.vakarux.instadownload.model.AppTheme

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var downloadTreeUri: String?
        get() = prefs.getString("download_tree_uri", null)
        set(value) = prefs.edit().putString("download_tree_uri", value).apply()

    var downloadFolderName: String
        get() = prefs.getString("download_folder_name", null) ?: DEFAULT_FOLDER_NAME
        set(value) = prefs.edit().putString("download_folder_name", value).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    var theme: AppTheme
        get() = enumValue(prefs.getString("theme", null), AppTheme.SYSTEM)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var quality: DownloadQuality
        get() = enumValue(prefs.getString("quality", null), DownloadQuality.AUTO)
        set(value) = prefs.edit().putString("quality", value.name).apply()

    var customWidth: Int
        get() = prefs.getInt("custom_width", 720).coerceAtLeast(320)
        set(value) = prefs.edit().putInt("custom_width", value.coerceAtLeast(320)).apply()

    var profileQuery: String?
        get() = prefs.getString("profile_query", null)
        set(value) = prefs.edit().putString("profile_query", value).apply()

    var profileTreeUri: String?
        get() = prefs.getString("profile_tree_uri", null)
        set(value) = prefs.edit().putString("profile_tree_uri", value).apply()

    var profileFolderName: String?
        get() = prefs.getString("profile_folder_name", null)
        set(value) = prefs.edit().putString("profile_folder_name", value).apply()

    fun targetWidth(): Int = when (effectiveQuality()) {
        DownloadQuality.DATA_SAVER -> 0
        DownloadQuality.CUSTOM -> customWidth.coerceAtLeast(320)
        else -> Int.MAX_VALUE
    }

    fun effectiveQuality(): DownloadQuality {
        if (quality != DownloadQuality.AUTO) return quality
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dataSaverOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return if (dataSaverOn) DownloadQuality.DATA_SAVER else DownloadQuality.BEST
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)

    companion object {
        const val DEFAULT_FOLDER_NAME = "Downloads/InstaDownload (default)"
    }
}

