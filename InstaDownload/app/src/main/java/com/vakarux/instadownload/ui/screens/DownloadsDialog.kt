package com.vakarux.instadownload.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateUtils
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vakarux.instadownload.R
import com.vakarux.instadownload.data.AppSettings
import com.vakarux.instadownload.data.DownloadedFiles
import com.vakarux.instadownload.data.DownloadHistory
import com.vakarux.instadownload.model.DownloadKind
import com.vakarux.instadownload.model.DownloadRecord
import com.vakarux.instadownload.model.kind
import com.vakarux.instadownload.ui.AppIcons
import com.vakarux.instadownload.ui.components.DownloadItemCard
import com.vakarux.instadownload.ui.components.ZoomableBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun DownloadsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(DownloadHistory.load(context)) }
    var selectedKind by remember { mutableStateOf(DownloadKind.ALL) }
    var previewRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var fullscreen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            if (fullscreen) fullscreen = false
            else if (previewRecord != null) previewRecord = null
            else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
            color = if (fullscreen) Color.Black else MaterialTheme.colorScheme.background
        ) {
            Column {
                if (!fullscreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(if (previewRecord != null) R.string.preview_label else R.string.downloads_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 20.dp, top = 16.dp, bottom = 12.dp)
                        )
                        TextButton(
                            onClick = {
                                if (previewRecord != null) previewRecord = null
                                else onDismiss()
                            },
                            modifier = Modifier.padding(top = 8.dp, end = 12.dp)
                        ) {
                            Text(stringResource(if (previewRecord != null) R.string.back_label else R.string.done_button))
                        }
                    }
                }

                val record = previewRecord
                if (record != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(if (fullscreen) Modifier else Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                    ) { MediaPreview(record, fullscreen) { fullscreen = it } }
                } else {
                    PrimaryScrollableTabRow(
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
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = AppIcons.VideoLibrary,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    if (records.isEmpty()) stringResource(R.string.downloads_empty)
                                    else stringResource(
                                        R.string.downloads_empty_kind,
                                        stringResource(selectedKind.labelRes)
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                if (records.isEmpty()) {
                                    Text(
                                        stringResource(R.string.downloads_empty_hint),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(visibleRecords, key = { it.uri }) { rec ->
                                DownloadItemCard(
                                    record = rec,
                                    onPreview = { previewRecord = rec },
                                    onDeleted = {
                                        DownloadHistory.remove(context, rec)
                                        records = records.filterNot { it.uri == rec.uri }
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
private fun ColumnScope.MediaFrame(fullscreen: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .then(
                if (fullscreen) Modifier.weight(1f).fillMaxWidth()
                else Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
            )
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaPreview(record: DownloadRecord, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val handleClipboardLabel = stringResource(R.string.handle_clipboard_label)
    val captionClipboardLabel = stringResource(R.string.caption_clipboard_label)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (record.mimeType.startsWith("video/")) VideoPreview(record, fullscreen, onFullscreenChange)
        else ImagePreview(record)
        if (fullscreen) return@Column
        val meta = record.meta
        val username = meta?.username?.takeIf { it.isNotBlank() }
        val caption = meta?.caption?.takeIf { it.isNotBlank() }
        username?.let { handle ->
            Text(
                "@$handle",
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            copyDownloadText(
                                context = context,
                                label = handleClipboardLabel,
                                text = "@$handle",
                                confirmation = R.string.handle_copied
                            )
                        }
                    )
            )
        }
        caption?.let { text ->
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            copyDownloadText(
                                context = context,
                                label = captionClipboardLabel,
                                text = text,
                                confirmation = R.string.caption_copied
                            )
                        }
                    )
                    .padding(top = 4.dp)
            )
        }
        meta?.url?.let { postUrl ->
            Text(
                postUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clickable { uriHandler.openUri(postUrl) }
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
private fun ColumnScope.ImagePreview(record: DownloadRecord) {
    val context = LocalContext.current
    val result by produceState<Result<ImageBitmap>?>(null, record.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                DownloadedFiles.previewBitmap(context, record, 1200)?.asImageBitmap()
                    ?: error("Preview unavailable")
            }
        }
    }

    MediaFrame {
        when {
            result == null -> CircularProgressIndicator(color = Color.White)
            result?.isSuccess == true -> ZoomableBitmap(result!!.getOrThrow())
            else -> Text(
                stringResource(R.string.preview_unavailable),
                color = Color.White,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.VideoPreview(
    record: DownloadRecord,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var failed by remember(record.uri) { mutableStateOf(false) }
    var playing by remember(record.uri) { mutableStateOf(false) }
    var durationMs by remember(record.uri) { mutableStateOf(0) }
    var positionMs by remember(record.uri) { mutableFloatStateOf(0f) }
    var seeking by remember(record.uri) { mutableStateOf(false) }
    var controlsVisible by remember(record.uri) { mutableStateOf(true) }
    val videoView = remember(record.uri) {
        VideoView(context).apply {
            setVideoURI(Uri.parse(record.uri))
            setOnPreparedListener {
                durationMs = it.duration
                it.start()
                playing = true
            }
            setOnCompletionListener { playing = false }
            setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
        }
    }
    val togglePlay = {
        if (playing) videoView.pause() else videoView.start()
        playing = !playing
    }
    DisposableEffect(videoView) {
        onDispose { videoView.stopPlayback() }
    }
    LaunchedEffect(fullscreen) { controlsVisible = true }
    LaunchedEffect(controlsVisible, playing, seeking, fullscreen) {
        if (fullscreen && controlsVisible && playing && !seeking) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(videoView, playing) {
        while (playing) {
            if (!seeking) positionMs = videoView.currentPosition.toFloat()
            delay(200)
        }
    }

    val controls: @Composable (Modifier, Color) -> Unit = { modifier, textColor ->
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = togglePlay,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize())
            ) {
                Icon(
                    imageVector = if (playing) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause_label else R.string.play_label),
                    modifier = Modifier.size(IconButtonDefaults.mediumIconSize)
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                val sliderInteraction = remember { MutableInteractionSource() }
                val sliderState = remember(record.uri) { SliderState() }
                if (!seeking && durationMs > 0) sliderState.value = positionMs / durationMs
                Slider(
                    state = sliderState,
                    onValueChange = {
                        seeking = true
                        sliderState.value = it
                        positionMs = it * durationMs
                        videoView.seekTo(positionMs.toInt())
                    },
                    onValueChangeFinished = { seeking = false },
                    enabled = durationMs > 0,
                    interactionSource = sliderInteraction,
                    thumb = { SliderDefaults.Thumb(sliderInteraction) },
                    track = { state ->
                        LinearWavyProgressIndicator(
                            progress = { state.value },
                            amplitude = { if (playing && !seeking) 1f else 0f },
                            trackColor = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
                Text(
                    DateUtils.formatElapsedTime((positionMs / 1000).toLong()) + " / " +
                        DateUtils.formatElapsedTime(durationMs / 1000L),
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor
                )
            }
            IconButton(onClick = { onFullscreenChange(!fullscreen) }) {
                Icon(
                    imageVector = if (fullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                    contentDescription = stringResource(
                        if (fullscreen) R.string.exit_fullscreen_label else R.string.fullscreen_label
                    ),
                    tint = textColor
                )
            }
        }
    }

    MediaFrame(fullscreen) {
        if (failed) {
            Text(stringResource(R.string.preview_unavailable), color = Color.White)
            return@MediaFrame
        }
        AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) {
                    if (!fullscreen) togglePlay() else controlsVisible = !controlsVisible
                }
        )
        if (fullscreen) {
            val shown = controlsVisible || !playing
            val controlsAlpha by animateFloatAsState(if (shown) 1f else 0f, label = "controls")
            if (shown || controlsAlpha > 0f) {
                controls(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .alpha(controlsAlpha)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(start = 12.dp, end = 4.dp, top = 24.dp, bottom = 24.dp),
                    Color.White
                )
            }
        }
    }
    if (!failed && !fullscreen) {
        controls(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copyDownloadText(
    context: Context,
    label: String,
    text: String,
    @androidx.annotation.StringRes confirmation: Int
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (AppSettings(context).hapticsEnabled) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, 120))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(35)
            }
        }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
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
