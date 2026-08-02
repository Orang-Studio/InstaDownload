package com.vakarux.instadownload

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build

enum class DownloadQuality(val label: String, val description: String) {
    AUTO("Auto", "Use Data Saver quality when Android Data Saver is on"),
    DATA_SAVER("Data Saver", "Use the smallest rendition Instagram provides"),
    BEST("Best quality", "Use the highest-quality rendition available")
}

enum class AppTheme(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var downloadTreeUri: String?
        get() = prefs.getString("download_tree_uri", null)
        set(value) = prefs.edit().putString("download_tree_uri", value).apply()

    var downloadFolderName: String
        get() = prefs.getString("download_folder_name", null) ?: "Downloads (default)"
        set(value) = prefs.edit().putString("download_folder_name", value).apply()

    var quality: DownloadQuality
        get() = enumValue(prefs.getString("quality", null), DownloadQuality.AUTO)
        set(value) = prefs.edit().putString("quality", value.name).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    var theme: AppTheme
        get() = enumValue(prefs.getString("theme", null), AppTheme.SYSTEM)
        set(value) = prefs.edit().putString("theme", value.name).apply()

    fun effectiveQuality(): DownloadQuality {
        if (quality != DownloadQuality.AUTO) return quality
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dataSaverOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return if (dataSaverOn) DownloadQuality.DATA_SAVER else DownloadQuality.BEST
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)
}
