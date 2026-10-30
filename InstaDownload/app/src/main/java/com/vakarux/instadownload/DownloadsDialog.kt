package com.vakarux.instadownload

import androidx.compose.runtime.Composable
import com.vakarux.instadownload.model.DownloadRecord
import com.vakarux.instadownload.ui.screens.DownloadsDialog as RealDownloadsDialog
import com.vakarux.instadownload.ui.screens.MediaPreview as RealMediaPreview

@Composable
fun DownloadsDialog(onDismiss: () -> Unit) = RealDownloadsDialog(onDismiss)

@Composable
fun MediaPreview(record: DownloadRecord, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) =
    RealMediaPreview(record, fullscreen, onFullscreenChange)
