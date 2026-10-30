package com.vakarux.instadownload

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.vakarux.instadownload.model.MediaResult
import com.vakarux.instadownload.ui.components.MediaViewer as RealMediaViewer
import com.vakarux.instadownload.ui.components.ZoomableBitmap as RealZoomableBitmap
import com.vakarux.instadownload.ui.screens.ProfileBrowser as RealProfileBrowser

@Composable
fun ProfileBrowser(
    username: String,
    settings: AppSettings,
    onFolderAccessLost: () -> Unit,
    onClose: () -> Unit,
) = RealProfileBrowser(username, settings, onFolderAccessLost, onClose)

@Composable
internal fun MediaViewer(
    items: List<MediaResult>?,
    placeholderUrl: String?,
    initialPage: Int = 0,
    onDismiss: () -> Unit
) = RealMediaViewer(items, placeholderUrl, initialPage, onDismiss)

@Composable
internal fun ZoomableBitmap(
    bitmap: ImageBitmap,
    onClick: (() -> Unit)? = null
) = RealZoomableBitmap(bitmap, onClick)
