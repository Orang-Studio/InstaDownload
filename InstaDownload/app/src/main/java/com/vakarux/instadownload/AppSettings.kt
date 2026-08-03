package com.vakarux.instadownload

import android.content.Context

enum class AppTheme(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

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

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: "") }.getOrDefault(fallback)

    companion object {
        const val DEFAULT_FOLDER_NAME = "Downloads/InstaDownload (default)"
    }
}
