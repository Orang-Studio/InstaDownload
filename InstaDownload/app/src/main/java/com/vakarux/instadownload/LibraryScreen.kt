package com.vakarux.instadownload

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vakarux.instadownload.ui.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

private val ThumbnailWidth = 96.dp
private val ThumbnailHeight = 128.dp

/**
 * Lists every file the app has downloaded, newest first, with the post's metadata.
 * Tap opens a file; long press starts multi-selection for sharing or deleting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsLibraryScreen(
    highlightedUris: Set<String>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val records by remember { DownloadHistory.records(context) }.collectAsState()
    val allRecords = records.orEmpty()
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<List<DownloadRecord>?>(null) }
    val fileExists = remember { mutableStateMapOf<String, Boolean>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val selectedRecords = allRecords.filter { it.uri in selectedUris }
    val inSelectionMode = selectedRecords.isNotEmpty()

    BackHandler {
        if (inSelectionMode) selectedUris = emptySet() else onBack()
    }

    LaunchedEffect(highlightedUris) {
        if (highlightedUris.isNotEmpty()) listState.scrollToItem(0)
    }

    fun showMessage(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun open(record: DownloadRecord) {
        if (fileExists[record.uri] == false) {
            showMessage(context.getString(R.string.library_file_missing))
            return
        }
        try {
            context.startActivity(DownloadedFiles.openIntent(context, record))
        } catch (e: ActivityNotFoundException) {
            showMessage(context.getString(R.string.library_no_app_to_open))
        } catch (e: Exception) {
            showMessage(e.message ?: context.getString(R.string.error_generic))
        }
    }

    fun share(toShare: List<DownloadRecord>) {
        val available = toShare.filter { fileExists[it.uri] != false }
        if (available.isEmpty()) {
            showMessage(context.getString(R.string.library_file_missing))
            return
        }
        runCatching {
            context.startActivity(
                DownloadedFiles.shareIntent(context, available, context.getString(R.string.library_share))
            )
        }.onFailure { showMessage(it.message ?: context.getString(R.string.error_generic)) }
    }

    fun toggleSelection(record: DownloadRecord) {
        selectedUris = if (record.uri in selectedUris) selectedUris - record.uri else selectedUris + record.uri
    }

    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(context.resources.getQuantityString(R.plurals.library_delete_title, toDelete.size, toDelete.size))
            },
            text = { Text(stringResource(R.string.library_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    selectedUris = emptySet()
                    coroutineScope.launch {
                        val failed = withContext(Dispatchers.IO) { deleteRecords(context, toDelete) }
                        val deleted = toDelete.size - failed
                        val message = if (failed == 0) {
                            context.resources.getQuantityString(R.plurals.library_deleted, deleted, deleted)
                        } else {
                            context.resources.getQuantityString(R.plurals.library_delete_failed, failed, failed)
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                }) {
                    Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.library_cancel)) }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_selected_count, selectedRecords.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedUris = emptySet() }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.library_clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = { selectedUris = allRecords.map { it.uri }.toSet() }) {
                            Icon(AppIcons.SelectAll, contentDescription = stringResource(R.string.library_select_all))
                        }
                        IconButton(onClick = { share(selectedRecords) }) {
                            Icon(AppIcons.Share, contentDescription = stringResource(R.string.library_share))
                        }
                        IconButton(onClick = { pendingDelete = selectedRecords }) {
                            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.library_delete))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.library_back))
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        when {
            records == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            allRecords.isEmpty() -> EmptyLibrary(Modifier.fillMaxSize().padding(innerPadding))

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allRecords, key = { it.uri }) { record ->
                    DownloadRow(
                        record = record,
                        isSelected = record.uri in selectedUris,
                        inSelectionMode = inSelectionMode,
                        isHighlighted = record.uri in highlightedUris,
                        fileExists = fileExists[record.uri],
                        onFileChecked = { exists -> fileExists[record.uri] = exists },
                        onClick = { if (inSelectionMode) toggleSelection(record) else open(record) },
                        onLongClick = { toggleSelection(record) },
                        onShare = { share(listOf(record)) },
                    )
                }
            }
        }
    }
}

/** Deletes the files and their library entries. Returns how many files could not be deleted. */
private fun deleteRecords(context: Context, records: List<DownloadRecord>): Int {
    val removed = records.filter { DownloadedFiles.delete(context, it) }.map { it.uri }.toSet()
    DownloadHistory.remove(context, removed)
    return records.size - removed.size
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            AppIcons.VideoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Text(
            stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    record: DownloadRecord,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    isHighlighted: Boolean,
    fileExists: Boolean?,
    onFileChecked: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    var captionExpanded by remember(record.uri) { mutableStateOf(false) }

    val sizeBytes by produceState(-1L, record.uri) {
        val (exists, size) = withContext(Dispatchers.IO) {
            val exists = DownloadedFiles.exists(context, record)
            exists to if (exists) DownloadedFiles.sizeBytes(context, record) else -1L
        }
        onFileChecked(exists)
        value = size
    }

    val containerColor = when {
        isSelected -> colorScheme.secondaryContainer
        isHighlighted -> colorScheme.primaryContainer
        else -> colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(modifier = Modifier.size(width = ThumbnailWidth, height = ThumbnailHeight)) {
                DownloadThumbnail(record, fileExists == false)
                if (inSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        record.username?.let { "@$it" } ?: stringResource(R.string.library_unknown_account),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontStyle = if (record.username == null) FontStyle.Italic else FontStyle.Normal
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (!inSelectionMode) {
                        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                            Icon(
                                AppIcons.Share,
                                contentDescription = stringResource(R.string.library_share),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Text(
                    record.caption ?: stringResource(R.string.library_no_caption),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = if (record.caption == null) FontStyle.Italic else FontStyle.Normal
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = if (captionExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (record.caption == null) Modifier else Modifier.combinedClickable(
                                onClick = { if (inSelectionMode) onClick() else captionExpanded = !captionExpanded },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    copyCaption(context, record.caption)
                                }
                            )
                        )
                )

                record.song?.let {
                    MetadataLine(stringResource(R.string.library_song_format, it))
                }

                if (record.takenAtSec > 0) {
                    MetadataLine(
                        stringResource(
                            R.string.library_posted_format,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(record.takenAtSec * 1000))
                        )
                    )
                }
                MetadataLine(
                    stringResource(
                        R.string.library_downloaded_format,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(record.downloadedAtMs))
                    )
                )

                val specs = listOfNotNull(
                    record.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
                    record.durationSec.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it.roundToLong()) },
                    sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
                )
                if (specs.isNotEmpty()) MetadataLine(specs.joinToString(" · "))

                val postUrl = record.postUrl
                if (postUrl != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable(enabled = !inSelectionMode) { runCatching { uriHandler.openUri(postUrl) } }
                    ) {
                        Text(
                            postUrl.removePrefix("https://").removePrefix("www."),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            AppIcons.OpenInNew,
                            contentDescription = stringResource(R.string.library_open_post),
                            tint = colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp).size(14.dp)
                        )
                    }
                } else {
                    MetadataLine(stringResource(R.string.library_no_link), italic = true)
                }

                if (fileExists == false) {
                    Text(
                        stringResource(R.string.library_file_missing),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun copyCaption(context: Context, caption: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.library_caption_clip_label), caption))
    // Android 13+ shows its own confirmation when something is copied.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.library_caption_copied, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MetadataLine(text: String, italic: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun DownloadThumbnail(record: DownloadRecord, isMissing: Boolean) {
    val context = LocalContext.current
    val maxSidePx = with(LocalDensity.current) { ThumbnailHeight.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, record.uri, isMissing) {
        if (isMissing) return@produceState
        value = withContext(Dispatchers.IO) {
            DownloadedFiles.thumbnail(context, record, maxSidePx)?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = stringResource(
                    if (record.isVideo) R.string.video_preview_description else R.string.image_preview_description
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (isMissing) 0.35f else 1f)
            )
        } else {
            Icon(
                imageVector = if (record.isVideo) AppIcons.Movie else AppIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
        }

        if (record.isVideo && loaded != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
