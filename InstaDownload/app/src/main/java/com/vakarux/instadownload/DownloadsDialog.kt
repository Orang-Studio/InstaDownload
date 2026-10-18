package com.vakarux.instadownload

import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vakarux.instadownload.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

private enum class DownloadKind(val labelRes: Int) {
    ALL(R.string.downloads_all),
    REELS(R.string.downloads_reels),
    POSTS(R.string.downloads_posts),
    STORIES(R.string.downloads_stories)
}

private val DownloadRecord.kind: DownloadKind
    get() = when {
        meta?.url?.contains("/stories/") == true -> DownloadKind.STORIES
        meta?.url?.contains("/reel") == true -> DownloadKind.REELS
        else -> DownloadKind.POSTS
    }

@Composable
fun DownloadsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(DownloadHistory.load(context)) }
    var previewRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var selectedKind by remember { mutableStateOf(DownloadKind.ALL) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(if (previewRecord == null) R.string.downloads_title else R.string.preview_label),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).padding(start = 20.dp, top = 16.dp, bottom = 12.dp)
                    )
                    TextButton(onClick = {
                        if (previewRecord == null) onDismiss() else previewRecord = null
                    }, modifier = Modifier.padding(top = 8.dp, end = 12.dp)) {
                        Text(stringResource(if (previewRecord == null) R.string.close_label else R.string.back_label))
                    }
                }
                val record = previewRecord
                if (record != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 20.dp)
                    ) { MediaPreview(record) }
                } else {
                    ScrollableTabRow(
                        selectedTabIndex = selectedKind.ordinal,
                        edgePadding = 12.dp
                    ) {
                        DownloadKind.entries.forEach { kind ->
                            Tab(
                                selected = selectedKind == kind,
                                onClick = { selectedKind = kind },
                                text = { Text(stringResource(kind.labelRes)) }
                            )
                        }
                    }
                    val visibleRecords = records.filter {
                        selectedKind == DownloadKind.ALL || it.kind == selectedKind
                    }
                    if (visibleRecords.isEmpty()) {
                        Text(
                            if (records.isEmpty()) stringResource(R.string.downloads_empty)
                            else stringResource(
                                R.string.downloads_empty_kind,
                                stringResource(selectedKind.labelRes)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleRecords, key = { it.uri }) { record ->
                                DownloadRow(
                                    record = record,
                                    onPreview = { previewRecord = record },
                                    onDeleted = {
                                        DownloadHistory.remove(context, record)
                                        records = records.filterNot { it.uri == record.uri }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onPreview: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val exists by produceState<Boolean?>(null, record.uri) {
        value = withContext(Dispatchers.IO) { DownloadedFiles.exists(context, record) }
    }
    val size by produceState(-1L, record.uri) {
        value = withContext(Dispatchers.IO) { DownloadedFiles.sizeBytes(context, record) }
    }
    val meta = record.meta

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DownloadThumbnail(
                    record = record,
                    exists = exists,
                    onClick = onPreview
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        meta?.username?.let { "@$it" } ?: stringResource(R.string.unknown_account),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        meta?.caption ?: stringResource(R.string.no_caption),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    val details = listOfNotNull(
                        meta?.song,
                        meta?.takenAtSec?.takeIf { it > 0 }
                            ?.let { stringResource(R.string.posted_date_format, formatDate(it * 1000)) },
                        record.takeIf { it.width > 0 && it.height > 0 }
                            ?.let { "${it.width}×${it.height}" },
                        record.durationSec.takeIf { it > 0 }
                            ?.let { DateUtils.formatElapsedTime(it.roundToLong()) },
                        size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                        stringResource(R.string.downloaded_date_format, formatDate(record.downloadedAt))
                    )
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    meta?.url?.let { postUrl ->
                        Text(
                            postUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .clickable { uriHandler.openUri(postUrl) }
                        )
                    } ?: Text(
                        stringResource(R.string.no_post_link),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            if (exists == false) {
                Text(
                    stringResource(R.string.file_missing),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(
                    onClick = onPreview,
                    enabled = exists != false,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(stringResource(R.string.preview_label), maxLines = 1) }
                TextButton(
                    onClick = { startFileAction(context) { DownloadedFiles.openIntent(context, record) } },
                    enabled = exists != false,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(stringResource(R.string.open_label), maxLines = 1) }
                TextButton(
                    onClick = { startFileAction(context) { DownloadedFiles.shareIntent(context, record) } },
                    enabled = exists != false,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(stringResource(R.string.share_label), maxLines = 1) }
                TextButton(
                    onClick = {
                        if (DownloadedFiles.delete(context, record)) onDeleted()
                        else Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) { Text(stringResource(R.string.delete_label), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DownloadThumbnail(record: DownloadRecord, exists: Boolean?, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, record.uri, exists) {
        if (exists != false) {
            value = withContext(Dispatchers.IO) {
                DownloadedFiles.previewBitmap(context, record, 320)?.asImageBitmap()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 132.dp)
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(enabled = exists != false, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(
                    if (record.mimeType.startsWith("video/")) {
                        R.string.video_preview_description
                    } else {
                        R.string.image_preview_description
                    }
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(132.dp)
            )
        } ?: if (exists == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(
                imageVector = if (record.mimeType.startsWith("video/")) AppIcons.Movie else AppIcons.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(32.dp)
            )
        }
        if (record.mimeType.startsWith("video/") && bitmap != null) {
            Icon(
                imageVector = AppIcons.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun MediaPreview(record: DownloadRecord) {
    val context = LocalContext.current
    Column {
        if (record.mimeType.startsWith("video/")) VideoPreview(record) else ImagePreview(record)
        record.meta?.caption?.let {
            Text(
                it,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                startFileAction(context) { DownloadedFiles.shareIntent(context, record) }
            }) { Text(stringResource(R.string.share_label)) }
        }
    }
}

@Composable
private fun ImagePreview(record: DownloadRecord) {
    val context = LocalContext.current
    val result by produceState<Result<ImageBitmap>?>(null, record.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                DownloadedFiles.previewBitmap(context, record, 1200)?.asImageBitmap()
                    ?: error("Preview unavailable")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when {
            result == null -> CircularProgressIndicator(color = Color.White)
            result?.isSuccess == true -> Image(
                bitmap = result!!.getOrThrow(),
                contentDescription = stringResource(R.string.image_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(300.dp)
            )
            else -> Text(
                stringResource(R.string.preview_unavailable),
                color = Color.White,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun VideoPreview(record: DownloadRecord) {
    val context = LocalContext.current
    var failed by remember(record.uri) { mutableStateOf(false) }
    val videoView = remember(record.uri) {
        VideoView(context).apply {
            setMediaController(MediaController(context))
            setVideoURI(Uri.parse(record.uri))
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
        }
    }
    DisposableEffect(videoView) {
        onDispose { videoView.stopPlayback() }
    }

    if (failed) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.preview_unavailable), color = Color.White)
        }
    } else {
        AndroidView(
            factory = { videoView },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )
    }
}

private fun startFileAction(context: android.content.Context, intent: () -> android.content.Intent) {
    try {
        context.startActivity(intent())
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, R.string.file_action_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun formatDate(timeMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timeMs))
