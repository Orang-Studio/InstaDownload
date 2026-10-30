package com.vakarux.instadownload

import androidx.compose.runtime.Composable
import com.vakarux.instadownload.ui.screens.FileManagerScreen as RealFileManagerScreen

@Composable
fun FileManagerScreen(
    settings: AppSettings,
    onChooseFolder: (() -> Unit)? = null
) {
    RealFileManagerScreen(
        settings = settings,
        onChooseFolder = onChooseFolder
    )
}
