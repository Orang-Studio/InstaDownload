package com.vakarux.instadownload.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.vakarux.instadownload.R
import com.vakarux.instadownload.data.AppSettings
import com.vakarux.instadownload.data.saveToDownloads
import com.vakarux.instadownload.model.AppTheme
import com.vakarux.instadownload.model.MediaResult
import com.vakarux.instadownload.network.InstagramDownloader
import com.vakarux.instadownload.ui.AppIcons
import com.vakarux.instadownload.ui.components.MediaViewer
import com.vakarux.instadownload.ui.theme.*
import com.vakarux.instadownload.util.Haptics
import com.vakarux.instadownload.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    initialUrl: String = "",
    initialUrlKey: Any = initialUrl,
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    settings: AppSettings,
    selectedFolderName: String = settings.downloadFolderName,
    onChooseFolder: () -> Unit = {},
    onThemeChanged: (AppTheme) -> Unit = {},
    onFolderAccessLost: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onDownloadsSaved: () -> Unit = {},
    onCheckPermissions: () -> Boolean = { true },
    onRequestPermissions: () -> Unit = {}
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var fullError by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<MediaResult>?>(null) }
    var deselectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var downloadComplete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var targetWidth by remember { mutableIntStateOf(settings.targetWidth()) }
    var profileToBrowse by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialUrlKey) {
        if (initialUrl.isNotBlank()) {
            url = initialUrl
            media = null
            urlError = null
            fullError = null
            downloadComplete = false
        }
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val genericError = stringResource(R.string.error_generic)
    val emptyUrlError = stringResource(R.string.error_empty_url)
    val invalidUrlError = stringResource(R.string.error_invalid_url)
    val folderAccessLostError = stringResource(R.string.error_folder_access_lost)
    val downloadFailedError = stringResource(R.string.error_download_failed)

    val isStory = UrlUtils.isStoryUrl(url.trim())

    val isDark = isAppInDarkTheme() || useDarkTheme

    val igGradient = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(IgPurpleDark, IgPinkDark, IgOrangeDark)
        } else {
            listOf(IgPurple, IgPink, IgOrange)
        }
    )

    val view = LocalView.current
    val activity = view.context as? ComponentActivity
    LaunchedEffect(Unit) {
        if (activity != null) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(
                    android.graphics.Color.TRANSPARENT
                )
            )
            val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            insetsController.isAppearanceLightStatusBars = false
        }
    }

    LaunchedEffect(url, targetWidth) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || !UrlUtils.isValidInstagramUrl(trimmed) || UrlUtils.isStoryUrl(trimmed) ||
            InstagramDownloader.isProfileUrl(trimmed)) {
            media = null
            return@LaunchedEffect
        }
        delay(350)
        isLoading = true
        urlError = null
        fullError = null
        media = null
        deselectedIndices = emptySet()
        downloadComplete = false
        val items = runCatching {
            withContext(Dispatchers.IO) {
                InstagramDownloader.getMediaItems(trimmed, targetWidth)
            }
        }
        isLoading = false
        if (items.isFailure) {
            fullError = items.exceptionOrNull()?.message ?: genericError
            return@LaunchedEffect
        }
        Haptics.hapticStart(context, settings.hapticsEnabled)
        media = items.getOrThrow()
    }

    viewerIndex?.let { index ->
        media?.let { MediaViewer(it, null, index) { viewerIndex = null } }
    }

    if (showSettings) {
        Dialog(
            onDismissRequest = {
                showSettings = false
                targetWidth = settings.targetWidth()
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            InstaDownloadTheme(darkTheme = isDark) {
                SettingsScreen(
                    settings = settings,
                    selectedFolderName = selectedFolderName,
                    onChooseFolder = onChooseFolder,
                    onThemeChanged = onThemeChanged,
                    onDismiss = {
                        showSettings = false
                        targetWidth = settings.targetWidth()
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onOpenLibrary,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.downloads_label),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.22f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = AppIcons.Settings,
                            contentDescription = stringResource(R.string.settings_content_description),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        modifier = Modifier
            .fillMaxSize()
            .background(igGradient)
            .imePadding()
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Spacer(modifier = Modifier.height(16.dp))

            // ── Hero ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = Color.White.copy(alpha = 0.8f)
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // ── Input card ────────────────────────────────────
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {

                    val pasteDescription = stringResource(R.string.paste_clipboard_description)
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            if (urlError != null) urlError = null
                            media = null
                            fullError = null
                        },
                        label = { Text(stringResource(R.string.url_field_label)) },
                        placeholder = { Text(stringResource(R.string.url_field_placeholder)) },
                        isError = urlError != null,
                        supportingText = {
                            if (urlError != null) {
                                Text(
                                    urlError!!,
                                    color = colorScheme.error
                                )
                            }
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val pastedText = clip.getItemAt(0).text?.toString() ?: ""
                                        url = pastedText
                                        if (urlError != null) urlError = null
                                        media = null
                                        fullError = null
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = AppIcons.ContentPaste,
                                    contentDescription = pasteDescription,
                                    tint = colorScheme.primary
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IgPink,
                            focusedLabelColor = IgPink,
                            cursorColor = IgPink
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isStory) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = IgOrange.copy(alpha = 0.12f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.story_not_supported_title),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = IgOrange,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    stringResource(R.string.story_not_supported_body),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val trimmed = url.trim()
                            when {
                                trimmed.isBlank() -> urlError = emptyUrlError
                                !UrlUtils.isValidInstagramUrl(trimmed) ->
                                    urlError = invalidUrlError
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !onCheckPermissions() ->
                                    onRequestPermissions()
                                InstagramDownloader.isProfileUrl(trimmed) ->
                                    profileToBrowse = InstagramDownloader.extractProfileUsername(trimmed)
                                else -> coroutineScope.launch {
                                    fullError = null
                                    val items = media ?: run {
                                        isLoading = true
                                        val fetched = runCatching {
                                            withContext(Dispatchers.IO) {
                                                InstagramDownloader.getMediaItems(trimmed, settings.targetWidth())
                                            }
                                        }
                                        isLoading = false
                                        if (fetched.isFailure) {
                                            fullError = fetched.exceptionOrNull()?.message
                                                ?: genericError
                                            return@launch
                                        }
                                        fetched.getOrThrow().also { media = it }
                                    }
                                    val itemsToSave = items.filterIndexed { i, _ ->
                                        i !in deselectedIndices
                                    }
                                    Haptics.hapticStart(context, settings.hapticsEnabled)
                                    isSaving = true
                                    var savedCount = 0
                                    var failedCount = 0
                                    var fellBackToDefaultFolder = false
                                    withContext(Dispatchers.IO) {
                                        for (item in itemsToSave) {
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                            runCatching {
                                                val outcome = saveToDownloads(item, context, settings)
                                                if (outcome.fellBackToDefaultFolder) {
                                                    fellBackToDefaultFolder = true
                                                }
                                            }.onSuccess {
                                                savedCount++
                                            }.onFailure {
                                                failedCount++
                                            }
                                        }
                                    }
                                    isSaving = false
                                    if (fellBackToDefaultFolder) {
                                        onFolderAccessLost()
                                    }
                                    if (savedCount > 0) {
                                        Haptics.hapticComplete(context, settings.hapticsEnabled)
                                        downloadComplete = true
                                        if (fellBackToDefaultFolder) {
                                            fullError = folderAccessLostError
                                        } else if (failedCount > 0) {
                                            fullError = "$savedCount saved, $failedCount failed"
                                        }
                                        delay(2500)
                                        downloadComplete = false
                                        media = null
                                        deselectedIndices = emptySet()
                                        onDownloadsSaved()
                                    } else {
                                        fullError = downloadFailedError
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !isSaving && !isStory && media?.size != deselectedIndices.size,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = IgPink,
                            contentColor = Color.White,
                            disabledContainerColor = IgPink.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.6f)
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 2.dp
                        )
                    ) {
                        when {
                            isSaving -> {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    stringResource(R.string.saving_label),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            downloadComplete -> {
                                Icon(
                                    imageVector = AppIcons.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.saved_label),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            else -> {
                                val isProfile = InstagramDownloader.isProfileUrl(url.trim())
                                Icon(
                                    imageVector = if (isProfile) AppIcons.Image else AppIcons.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(if (isProfile) R.string.open_profile_label else R.string.download_label),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Preview (inline) ──────────────────────────────
            AnimatedVisibility(
                visible = media != null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                val items = media ?: emptyList()
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            (if (items.size > 1) stringResource(R.string.items_count_format, items.size)
                            else stringResource(R.string.preview_label)) +
                                (items.firstOrNull { it.reduced }
                                    ?.let { stringResource(R.string.data_saver_suffix_format, it.width) } ?: ""),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items.forEachIndexed { index, item ->
                                MediaThumbnail(
                                    item = item,
                                    isCarousel = items.size > 1,
                                    isSelected = index !in deselectedIndices,
                                    onToggleSelected = {
                                        deselectedIndices = if (index in deselectedIndices)
                                            deselectedIndices - index else deselectedIndices + index
                                    },
                                    onOpen = { viewerIndex = index }
                                )
                            }
                        }
                    }
                }
            }

            // ── Error card (copyable) ─────────────────────────
            AnimatedVisibility(
                visible = fullError != null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200))
            ) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val errorLabel = stringResource(R.string.error_title)
                            Text(
                                errorLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Row {
                                val copyErrorDescription = stringResource(R.string.copy_error_description)
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = android.content.ClipData.newPlainText(errorLabel, fullError)
                                    clipboard.setPrimaryClip(clip)
                                }) {
                                    Icon(
                                        imageVector = AppIcons.ContentCopy,
                                        contentDescription = copyErrorDescription,
                                        tint = colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                TextButton(onClick = { fullError = null }) {
                                    Text(
                                        stringResource(R.string.dismiss_label),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = colorScheme.onErrorContainer
                                        )
                                    )
                                }
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = fullError ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            stringResource(R.string.update_notice),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                uriHandler.openUri("https://github.com/Orang-Studio/InstaDownload/releases/latest")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.error,
                                contentColor = colorScheme.onError
                            )
                        ) {
                            Icon(
                                imageVector = AppIcons.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.update_button),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── GitHub credit ─────────────────────────────────
            GitHubCredit()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    profileToBrowse?.let { username ->
        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false) }.apply { targetState = true },
            enter = slideInVertically(tween(250)) { it / 4 } + fadeIn(tween(250))
        ) {
            ProfileBrowser(
                username = username,
                settings = settings,
                onFolderAccessLost = onFolderAccessLost,
                onClose = { profileToBrowse = null }
            )
        }
    }
}

@Composable
fun GitHubCredit() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        version?.let {
            Text(
                stringResource(R.string.version_label_format, it),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White.copy(alpha = 0.6f)
                )
            )
        }
        TextButton(
            onClick = { uriHandler.openUri("https://github.com/Orang-Studio/InstaDownload") },
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.github),
                    contentDescription = stringResource(R.string.github_content_description),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.made_by_label),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color.White.copy(alpha = 0.85f)
                )
            )
        }
        TextButton(onClick = { uriHandler.openUri("https://github.com/Orang-Studio/InstaDownload/blob/main/PRIVACY.md") }) {
            Text(
                stringResource(R.string.privacy_policy_label),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White.copy(alpha = 0.6f)
                )
            )
        }
    }
}

@Composable
fun MediaThumbnail(
    item: MediaResult,
    isCarousel: Boolean = false,
    isSelected: Boolean = true,
    onToggleSelected: () -> Unit = {},
    onOpen: () -> Unit = {}
) {
    val previewUrl = item.previewUrl
    var loadFailed by remember(previewUrl) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, previewUrl) {
        if (previewUrl == null) {
            value = null
            return@produceState
        }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = InstagramDownloader.fetchBytes(previewUrl)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (bmp == null) loadFailed = true
        value = bmp
    }
    val context = LocalContext.current
    val sizeBytes by produceState(-1L, item.url) {
        value = withContext(Dispatchers.IO) {
            runCatching { InstagramDownloader.contentLength(item.url) }.getOrDefault(-1L)
        }
    }
    val meta = listOfNotNull(
        item.takeIf { it.width in 1 until Int.MAX_VALUE && it.height > 0 }?.let { "${it.width}×${it.height}" },
        item.durationSec.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it.roundToLong()) },
        sizeBytes.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(width = 120.dp, height = 158.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(width = 120.dp, height = 150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp,
                        contentDescription = stringResource(
                            if (item.isVideo) R.string.video_preview_description else R.string.image_preview_description
                        ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (isCarousel && !isSelected) 0.35f else 1f)
                    )
                    previewUrl == null || loadFailed -> Icon(
                        imageVector = if (item.isVideo) AppIcons.Movie else AppIcons.Image,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp)
                    )
                    else -> CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (item.isVideo && bmp != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            if (isCarousel) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelected() },
                    colors = CheckboxDefaults.colors(checkedColor = IgPink),
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.width(120.dp).padding(top = 4.dp)
            )
        }
    }
}

