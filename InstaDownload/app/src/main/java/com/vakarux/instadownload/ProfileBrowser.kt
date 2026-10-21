package com.vakarux.instadownload

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import android.text.format.DateUtils
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Calendar
import java.util.TimeZone

enum class DatePreset(@StringRes val labelRes: Int) {
    ANY(R.string.filter_date_any),
    WEEK(R.string.filter_date_week),
    MONTH(R.string.filter_date_month),
    YEAR(R.string.filter_date_year),
    CUSTOM(R.string.filter_date_custom),
}

data class PostFilter(
    val types: Set<PostType> = PostType.entries.toSet(),
    val datePreset: DatePreset = DatePreset.ANY,
    val fromSec: Long? = null,
    val toSec: Long? = null,
    val caption: String = "",
) {
    fun apply(posts: List<ProfilePost>): List<ProfilePost> {
        val query = caption.trim()
        return posts.filter { post ->
            post.type in types &&
                (fromSec == null || post.takenAtSec >= fromSec) &&
                (toSec == null || post.takenAtSec < toSec) &&
                (query.isEmpty() || post.caption.contains(query, ignoreCase = true))
        }
    }
}

private fun presetStartSec(preset: DatePreset): Long? {
    val cal = Calendar.getInstance()
    when (preset) {
        DatePreset.WEEK -> cal.add(Calendar.DAY_OF_YEAR, -7)
        DatePreset.MONTH -> cal.add(Calendar.DAY_OF_YEAR, -30)
        DatePreset.YEAR -> {
            cal.clear()
            cal.set(Calendar.getInstance().get(Calendar.YEAR), Calendar.JANUARY, 1)
        }
        else -> return null
    }
    return cal.timeInMillis / 1000
}

private fun pickerDayToLocalSec(utcMillis: Long, plusDays: Int = 0): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
        add(Calendar.DAY_OF_YEAR, plusDays)
    }.timeInMillis / 1000
}

private val thumbCache = LruCache<String, ImageBitmap>(60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBrowser(
    username: String,
    settings: AppSettings,
    onFolderAccessLost: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targetWidth = remember { settings.targetWidth() }

    var posts by remember { mutableStateOf<List<ProfilePost>>(emptyList()) }
    var profilePicUrl by remember { mutableStateOf<String?>(null) }
    var previewPost by remember { mutableStateOf<ProfilePost?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(PostFilter()) }
    var deselected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var saveProgress by remember { mutableStateOf(0 to 0) }
    var saveResult by remember { mutableStateOf<String?>(null) }

    val oldestSec = posts.lastOrNull()?.takenAtSec
    val reachedDateStart = filter.fromSec != null && oldestSec != null && oldestSec < filter.fromSec!!
    val loading = !finished && !paused && error == null && !reachedDateStart

    LaunchedEffect(loading, cursor) {
        if (!loading) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                InstagramDownloader.fetchProfilePage(username, cursor, settings)
            }
        }.onSuccess { page ->
            posts = (posts + page.posts).distinctBy { it.code }
            page.profilePicUrl?.let { profilePicUrl = it }
            if (page.nextCursor == null) finished = true else cursor = page.nextCursor
        }.onFailure {
            if (it !is CancellationException) error = it.message ?: context.getString(R.string.error_generic)
        }
    }

    val visible = remember(posts, filter) { filter.apply(posts) }
    val selected = visible.filter { it.code !in deselected }
    val fileCount = selected.sumOf { it.mediaCount }

    BackHandler { if (saveJob == null) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = saveJob == null) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.close_label))
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatar = rememberThumbnail(profilePicUrl)
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            avatar?.let {
                                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                        Column {
                            Text(stringResource(R.string.profile_title_format, username))
                            Text(
                                when {
                                    loading -> stringResource(R.string.profile_loading_format, posts.size)
                                    finished -> stringResource(R.string.profile_all_loaded_format, posts.size)
                                    else -> stringResource(R.string.profile_loaded_format, posts.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (loading) TextButton(onClick = { paused = true }) {
                        Text(stringResource(R.string.stop_loading_label))
                    } else if (paused && !finished) TextButton(onClick = { paused = false }) {
                        Text(stringResource(R.string.load_more_label))
                    }
                }
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
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = {
                            saveJob?.let { it.cancel(); return@Button }
                            val toSave = selected
                            val total = fileCount
                            saveResult = null
                            saveJob = scope.launch {
                                var saved = 0
                                var failed = 0
                                var fellBack = false
                                try {
                                    toSave.forEach { post ->
                                        saveProgress = (saved + failed + 1) to total
                                        val items = withContext(Dispatchers.IO) {
                                            runCatching {
                                                InstagramDownloader.getMediaItems("https://www.instagram.com/p/${post.code}/", targetWidth)
                                            }
                                        }.getOrElse {
                                            failed += post.mediaCount
                                            return@forEach
                                        }
                                        items.forEach { item ->
                                            saveProgress = (saved + failed + 1) to total
                                            withContext(Dispatchers.IO) {
                                                runCatching { saveToDownloads(item, context, settings).fellBackToDefaultFolder }
                                            }.onSuccess {
                                                saved++
                                                if (it) fellBack = true
                                            }.onFailure { failed++ }
                                        }
                                    }
                                } finally {
                                    if (fellBack) onFolderAccessLost()
                                    saveResult = if (failed == 0) context.getString(R.string.profile_saved_format, saved)
                                        else context.getString(R.string.profile_saved_failed_format, saved, failed)
                                    saveJob = null
                                }
                            }
                        },
                        enabled = saveJob != null || fileCount > 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IgPink, contentColor = Color.White)
                    ) {
                        Text(
                            if (saveJob != null) stringResource(
                                R.string.profile_saving_format, saveProgress.first, saveProgress.second
                            ) else stringResource(R.string.profile_download_format, fileCount),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = IgPink)

            FilterChips(filter = filter, onChange = { filter = it })

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.profile_selected_format, selected.size, visible.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { deselected = emptySet() }) {
                    Text(stringResource(R.string.select_all_label))
                }
                TextButton(onClick = { deselected = deselected + visible.map { it.code } }) {
                    Text(stringResource(R.string.select_none_label))
                }
            }

            error?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 6
                        )
                        TextButton(onClick = { error = null }, modifier = Modifier.align(Alignment.End)) {
                            Text(stringResource(R.string.retry_label))
                        }
                    }
                }
            }

            if (visible.isEmpty() && !loading) {
                Text(
                    stringResource(R.string.profile_no_matches),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visible, key = { it.code }) { post ->
                    PostCell(
                        post = post,
                        isSelected = post.code !in deselected,
                        onToggle = {
                            deselected = if (post.code in deselected) deselected - post.code else deselected + post.code
                        },
                        onPreview = { previewPost = post }
                    )
                }
            }
        }
    }

    previewPost?.let { PostPreview(it, targetWidth) { previewPost = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChips(filter: PostFilter, onChange: (PostFilter) -> Unit) {
    val context = LocalContext.current
    var openMenu by remember { mutableStateOf<String?>(null) }
    var showCaptionDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            FilterChip(
                selected = filter.types.size != PostType.entries.size,
                onClick = { openMenu = "type" },
                label = {
                    Text(
                        if (filter.types.size == PostType.entries.size) stringResource(R.string.filter_type_all)
                        else filter.types.joinToString { context.getString(typeLabel(it)) }
                    )
                }
            )
            DropdownMenu(expanded = openMenu == "type", onDismissRequest = { openMenu = null }) {
                PostType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(stringResource(typeLabel(type))) },
                        leadingIcon = { Checkbox(checked = type in filter.types, onCheckedChange = null) },
                        onClick = {
                            val types = if (type in filter.types) filter.types - type else filter.types + type
                            if (types.isNotEmpty()) onChange(filter.copy(types = types))
                        }
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = filter.datePreset != DatePreset.ANY,
                onClick = { openMenu = "date" },
                label = {
                    Text(
                        if (filter.datePreset == DatePreset.CUSTOM && filter.fromSec != null && filter.toSec != null)
                            DateUtils.formatDateRange(
                                context, filter.fromSec * 1000, filter.toSec * 1000 - 1,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                            )
                        else stringResource(filter.datePreset.labelRes)
                    )
                }
            )
            DropdownMenu(expanded = openMenu == "date", onDismissRequest = { openMenu = null }) {
                DatePreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(stringResource(preset.labelRes)) },
                        onClick = {
                            openMenu = null
                            if (preset == DatePreset.CUSTOM) showDatePicker = true
                            else onChange(filter.copy(datePreset = preset, fromSec = presetStartSec(preset), toSec = null))
                        }
                    )
                }
            }
        }

        FilterChip(
            selected = filter.caption.isNotBlank(),
            onClick = { showCaptionDialog = true },
            label = {
                Text(
                    if (filter.caption.isBlank()) stringResource(R.string.filter_caption)
                    else stringResource(R.string.filter_caption_format, filter.caption.trim())
                )
            }
        )

        if (filter != PostFilter()) {
            AssistChip(
                onClick = { onChange(PostFilter()) },
                label = { Text(stringResource(R.string.reset_filters_label)) }
            )
        }
    }

    if (showCaptionDialog) {
        var text by remember { mutableStateOf(filter.caption) }
        AlertDialog(
            onDismissRequest = { showCaptionDialog = false },
            title = { Text(stringResource(R.string.filter_caption_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.filter_caption_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(filter.copy(caption = text))
                    showCaptionDialog = false
                }) { Text(stringResource(R.string.done_button)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    onChange(filter.copy(caption = ""))
                    showCaptionDialog = false
                }) { Text(stringResource(R.string.clear_label)) }
            }
        )
    }

    if (showDatePicker) {
        val state = rememberDateRangePickerState(
            yearRange = 2010..Calendar.getInstance().get(Calendar.YEAR),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis ?: return@TextButton
                        val end = state.selectedEndDateMillis ?: start
                        onChange(
                            filter.copy(
                                datePreset = DatePreset.CUSTOM,
                                fromSec = pickerDayToLocalSec(start),
                                toSec = pickerDayToLocalSec(end, plusDays = 1)
                            )
                        )
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.done_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel_label)) }
            }
        ) {
            DateRangePicker(
                state = state,
                title = {
                    DateRangePickerDefaults.DateRangePickerTitle(
                        displayMode = state.displayMode,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                },
                headline = {
                    DateRangePickerDefaults.DateRangePickerHeadline(
                        selectedStartDateMillis = state.selectedStartDateMillis,
                        selectedEndDateMillis = state.selectedEndDateMillis,
                        displayMode = state.displayMode,
                        dateFormatter = remember { DatePickerDefaults.dateFormatter() },
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@StringRes
private fun typeLabel(type: PostType): Int = when (type) {
    PostType.PHOTO -> R.string.filter_type_photos
    PostType.VIDEO -> R.string.filter_type_videos
    PostType.CAROUSEL -> R.string.filter_type_carousels
}

@Composable
private fun rememberThumbnail(previewUrl: String?, downsample: Boolean = true): ImageBitmap? {
    val bitmap by produceState(previewUrl?.let { thumbCache.get(it) }, previewUrl) {
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
        }?.also { thumbCache.put(previewUrl, it) }
    }
    return bitmap
}

@Composable
private fun PostCell(post: ProfilePost, isSelected: Boolean, onToggle: () -> Unit, onPreview: () -> Unit) {
    val bitmap = rememberThumbnail(post.previewUrl)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color.Black.copy(alpha = 0.15f))
            .combinedClickable(onClick = onToggle, onLongClick = onPreview)
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(
                    if (post.type == PostType.VIDEO) R.string.video_preview_description else R.string.image_preview_description
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isSelected) 1f else 0.35f)
            )
        }
        val badge = when (post.type) {
            PostType.VIDEO -> AppIcons.PlayArrow to null
            PostType.CAROUSEL -> AppIcons.Image to post.mediaCount.toString()
            PostType.PHOTO -> null
        }
        badge?.let { (icon, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                label?.let { Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = IgPink),
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun PostPreview(post: ProfilePost, targetWidth: Int, onDismiss: () -> Unit) {
    val items by produceState<List<MediaResult>?>(null, post.code) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                InstagramDownloader.getMediaItems("https://www.instagram.com/p/${post.code}/", targetWidth)
            }.getOrNull()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
        ) {
            val loaded = items
            if (loaded.isNullOrEmpty()) {
                rememberThumbnail(post.previewUrl)?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (loaded == null) CircularProgressIndicator(color = Color.White)
                return@Box
            }
            HorizontalPager(state = rememberPagerState { loaded.size }, modifier = Modifier.fillMaxSize()) { page ->
                val item = loaded[page]
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
                        (rememberThumbnail(item.url, downsample = false) ?: rememberThumbnail(item.previewUrl))?.let {
                            Image(
                                it,
                                contentDescription = stringResource(R.string.image_preview_description),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
