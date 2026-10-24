package com.vakarux.instadownload

import android.net.Uri
import android.text.format.DateUtils
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vakarux.instadownload.ui.AppIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryBrowser(
    storyUrl: String,
    session: IgSession,
    settings: AppSettings,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targetWidth = remember { settings.targetWidth() }
    val genericError = stringResource(R.string.error_generic)
    val savedFormat = stringResource(R.string.story_saved_format)
    val savedFailedFormat = stringResource(R.string.story_saved_failed_format)

    var page by remember { mutableStateOf<StoryPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var deselected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<StoryMedia?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saveProgress by remember { mutableStateOf(0 to 0) }
    var saveResult by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(storyUrl, session.sessionId, targetWidth, reloadKey) {
        loading = true
        error = null
        page = null
        deselected = emptySet()
        runCatching {
            withContext(Dispatchers.IO) {
                InstagramDownloader.fetchActiveStories(storyUrl, session, targetWidth)
            }
        }.onSuccess {
            page = it
        }.onFailure {
            if (it !is CancellationException) error = it.message ?: genericError
        }
        loading = false
    }

    val stories = page?.stories.orEmpty()
    val selected = stories.filter { it.id !in deselected }

    BackHandler {
        saveJob?.cancel() ?: onClose()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            page?.let { stringResource(R.string.story_title_format, it.username) }
                                ?: stringResource(R.string.stories_label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (stories.isNotEmpty()) {
                            Text(
                                stringResource(R.string.story_count_format, stories.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = saveJob == null) {
                        Icon(
                            AppIcons.Close,
                            contentDescription = stringResource(R.string.close_label),
                        )
                    }
                },
                actions = {
                    if (!loading && error != null) {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(
                                AppIcons.Refresh,
                                contentDescription = stringResource(R.string.retry_label),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    saveResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = {
                            val running = saveJob
                            if (running != null) {
                                running.cancel()
                                return@Button
                            }
                            val toSave = selected
                            val savedIds = mutableSetOf<String>()
                            val total = toSave.size
                            saveResult = null
                            saveJob = scope.launch {
                                var saved = 0
                                var failed = 0
                                var cancelled = false
                                try {
                                    toSave.forEachIndexed { index, story ->
                                        saveProgress = (index + 1) to total
                                        try {
                                            withContext(Dispatchers.IO) {
                                                saveStoryToGallery(story.media, context, session)
                                            }
                                            saved++
                                            savedIds += story.id
                                        } catch (cancellation: CancellationException) {
                                            throw cancellation
                                        } catch (_: Exception) {
                                            failed++
                                        }
                                    }
                                } catch (_: CancellationException) {
                                    cancelled = true
                                } finally {
                                    if (!cancelled) {
                                        deselected += savedIds
                                        saveResult = if (failed == 0) {
                                            savedFormat.format(saved)
                                        } else {
                                            savedFailedFormat.format(saved, failed)
                                        }
                                    }
                                    saveJob = null
                                }
                            }
                        },
                        enabled = saveJob != null || selected.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            if (saveJob != null) {
                                stringResource(
                                    R.string.story_saving_format,
                                    saveProgress.first,
                                    saveProgress.second,
                                )
                            } else {
                                stringResource(R.string.story_download_format, selected.size)
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = IgPink,
                )
            }

            if (stories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.story_selected_format, selected.size, stories.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { deselected = emptySet() }) {
                        Text(stringResource(R.string.select_all_label))
                    }
                    TextButton(onClick = { deselected = stories.map { it.id }.toSet() }) {
                        Text(stringResource(R.string.stories_deselect_all))
                    }
                }
            }

            error?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { reloadKey++ },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.retry_label))
                        }
                    }
                }
            }

            if (!loading && error == null && stories.isEmpty()) {
                Text(
                    stringResource(R.string.story_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 124.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(stories, key = { it.id }) { story ->
                    StoryCell(
                        story = story,
                        index = stories.indexOf(story),
                        isSelected = story.id !in deselected,
                        isShared = story.id == page?.sharedMediaId,
                        session = session,
                        onToggle = {
                            deselected = if (story.id in deselected) {
                                deselected - story.id
                            } else {
                                deselected + story.id
                            }
                        },
                        onPreview = { preview = story },
                    )
                }
            }
        }
    }

    preview?.let { story ->
        StoryPreviewDialog(
            story = story,
            session = session,
            onDismiss = { preview = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoryCell(
    story: StoryMedia,
    index: Int,
    isSelected: Boolean,
    isShared: Boolean,
    session: IgSession,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    val item = story.media
    val thumbnail = rememberStoryThumbnail(item.previewUrl, session)
    val details = listOfNotNull(
        item.durationSec.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it.roundToLong()) },
        item.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
        item.meta?.takenAtSec?.takeIf { it > 0 }
            ?.let { DateUtils.getRelativeTimeSpanString(it * 1000).toString() },
    )

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.16f))
                .then(
                    if (isShared) Modifier.border(
                        2.dp,
                        IgPink,
                        RoundedCornerShape(14.dp),
                    ) else Modifier
                )
                .combinedClickable(onClick = onPreview, onLongClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = stringResource(
                        if (item.isVideo) R.string.video_preview_description
                        else R.string.image_preview_description,
                    ),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isSelected) 1f else 0.35f),
                )
            } ?: if (item.previewUrl == null) {
                Icon(
                    imageVector = if (item.isVideo) AppIcons.Movie else AppIcons.Image,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(36.dp),
                )
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            }

            Text(
                "#${index + 1}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            if (isShared) {
                Text(
                    stringResource(R.string.shared_story_label),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(IgPink, RoundedCornerShape(bottomEnd = 8.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = IgPink),
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if (item.isVideo) {
                Icon(
                    imageVector = AppIcons.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                )
            }
        }
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StoryPreviewDialog(
    story: StoryMedia,
    session: IgSession,
    onDismiss: () -> Unit,
) {
    val item = story.media
    val bitmap = if (item.isVideo) null else {
        rememberStoryThumbnail(item.previewUrl, session, downsample = false)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(contentAlignment = Alignment.Center) {
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
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.image_preview_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    Icon(
                        AppIcons.Close,
                        contentDescription = stringResource(R.string.close_label),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberStoryThumbnail(
    url: String?,
    session: IgSession,
    downsample: Boolean = true,
): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, url, session.sessionId, downsample) {
        if (url == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InstagramDownloader.fetchBytes(url, session)
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = if (downsample && maxOf(options.outWidth, options.outHeight) >= 900) 2 else 1
                options.inJustDecodeBounds = false
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}
