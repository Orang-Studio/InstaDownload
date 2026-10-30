package com.vakarux.instadownload.model

import androidx.annotation.StringRes
import com.vakarux.instadownload.R

enum class DownloadQuality(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    AUTO(R.string.quality_auto_label, R.string.quality_auto_description),
    DATA_SAVER(R.string.quality_data_saver_label, R.string.quality_data_saver_description),
    BEST(R.string.quality_best_label, R.string.quality_best_description),
    CUSTOM(R.string.quality_custom_label, R.string.quality_custom_description)
}

enum class AppTheme(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system_label),
    LIGHT(R.string.theme_light_label),
    DARK(R.string.theme_dark_label)
}

