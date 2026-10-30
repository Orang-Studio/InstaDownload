package com.vakarux.instadownload.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vakarux.instadownload.R
import com.vakarux.instadownload.model.MediaResult
import com.vakarux.instadownload.network.InstagramDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

private val mediaThumbCache = LruCache<String, ImageBitmap>(60)

@Composable
fun rememberMediaThumbnail(previewUrl: String?, downsample: Boolean = true): ImageBitmap? {
    val bitmap by produceState(previewUrl?.let { mediaThumbCache.get(it) }, previewUrl) {
        if (value != null || previewUrl == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InstagramDownloader.fetchBytes(previewUrl)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = if (downsample && options.outWidth >= 600) 2 else 1
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }?.also { mediaThumbCache.put(previewUrl, it) }
    }
    return bitmap
}

@Composable
fun MediaViewer(
    items: List<MediaResult>?,
    placeholderUrl: String?,
    initialPage: Int = 0,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
        ) {
            if (items.isNullOrEmpty()) {
                rememberMediaThumbnail(placeholderUrl)?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (items == null) CircularProgressIndicator(color = Color.White)
                return@Box
            }
            HorizontalPager(state = rememberPagerState(initialPage) { items.size }, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (item.isVideo) {
                        AndroidView(
                            factory = { context ->
                                VideoView(context).apply {
                                    setVideoURI(Uri.parse(item.url))
                                    setOnPreparedListener {
                                        it.isLooping = true
                                        it.start()
                                    }
                                }
                            },
                            onRelease = { it.stopPlayback() },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        (rememberMediaThumbnail(item.url, downsample = false) ?: rememberMediaThumbnail(item.previewUrl))?.let {
                            ZoomableBitmap(it, onClick = onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ZoomableBitmap(bitmap: ImageBitmap, onClick: (() -> Unit)? = null) {
    val state = rememberZoomableState()
    LaunchedEffect(bitmap) {
        state.setContentLocation(
            ZoomableContentLocation.scaledInsideAndCenterAligned(Size(bitmap.width.toFloat(), bitmap.height.toFloat()))
        )
    }
    Image(
        bitmap,
        contentDescription = stringResource(R.string.image_preview_description),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .zoomable(state, onClick = onClick?.let { { _ -> it() } })
    )
}

