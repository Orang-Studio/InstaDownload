package com.vakarux.instadownload

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.vakarux.instadownload.ui.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import kotlin.math.roundToLong

// Instagram brand gradient colors
private val IgPurple = Color(0xFF833AB4)
private val IgPink   = Color(0xFFE1306C)
private val IgOrange = Color(0xFFF77737)

// Dark equivalents (desaturated per MD3 dark mode guidance)
private val IgPurpleDark = Color(0xFF2D1B2E)
private val IgPinkDark   = Color(0xFF4A1428)
private val IgOrangeDark = Color(0xFF3D1A0A)

class MainActivity : ComponentActivity() {

    private val appSettings by lazy { AppSettings(this) }
    private val selectedFolderName = mutableStateOf(AppSettings.DEFAULT_FOLDER_NAME)
    private class SharedUrl(val url: String)
    private val sharedUrl = mutableStateOf(SharedUrl(""))

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':') }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.selected_folder_default)
        appSettings.downloadTreeUri = uri.toString()
        appSettings.downloadFolderName = name
        selectedFolderName.value = name
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* permission result handled inline */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolderName.value = appSettings.downloadFolderName
        sharedUrl.value = SharedUrl(handleSharedIntent(intent))

        setContent {
            val settings = appSettings
            var selectedTheme by remember { mutableStateOf(settings.theme) }
            val useDarkTheme = when (selectedTheme) {
                AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            InstaDownloadTheme(darkTheme = useDarkTheme) {
                InstagramDownloaderScreen(
                    initialUrl = sharedUrl.value.url,
                    initialUrlKey = sharedUrl.value,
                    useDarkTheme = useDarkTheme,
                    settings = settings,
                    selectedFolderName = selectedFolderName.value,
                    onChooseFolder = { folderPickerLauncher.launch(null) },
                    onThemeChanged = { selectedTheme = it },
                    onFolderAccessLost = { selectedFolderName.value = AppSettings.DEFAULT_FOLDER_NAME }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl.value = SharedUrl(handleSharedIntent(intent))
    }

    private fun handleSharedIntent(intent: Intent): String {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (isValidInstagramUrl(text)) return text
        }
        return ""
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun InstagramDownloaderScreen(
        initialUrl: String = "",
        initialUrlKey: Any = initialUrl,
        useDarkTheme: Boolean = isSystemInDarkMode(),
        settings: AppSettings = AppSettings(this),
        selectedFolderName: String = settings.downloadFolderName,
        onChooseFolder: () -> Unit = {},
        onThemeChanged: (AppTheme) -> Unit = {},
        onFolderAccessLost: () -> Unit = {}
    ) {
        var url by remember { mutableStateOf(initialUrl) }
        var isLoading by remember { mutableStateOf(false) }
        var isSaving by remember { mutableStateOf(false) }
        var urlError by remember { mutableStateOf<String?>(null) }
        var fullError by remember { mutableStateOf<String?>(null) }
        var media by remember { mutableStateOf<List<MediaResult>?>(null) }
        var deselectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
        var downloadComplete by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var targetWidth by remember { mutableIntStateOf(settings.targetWidth()) }

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

        val isStory = isStoryUrl(url.trim())

        val igGradient = Brush.verticalGradient(
            colors = if (useDarkTheme) {
                listOf(IgPurpleDark, IgPinkDark, IgOrangeDark)
            } else {
                listOf(IgPurple, IgPink, IgOrange)
            }
        )

        LaunchedEffect(url, targetWidth) {
            val trimmed = url.trim()
            if (trimmed.isBlank() || !isValidInstagramUrl(trimmed) || isStoryUrl(trimmed)) {
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
                fullError = items.exceptionOrNull()?.message ?: context.getString(R.string.error_generic)
                return@LaunchedEffect
            }
            hapticStart(context, settings.hapticsEnabled)
            media = items.getOrThrow()
        }

        if (showSettings) {
            SettingsDialog(
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

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier
                .background(igGradient)
                .imePadding()
        ) { innerPadding ->

            // Loading bar — top of screen
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Spacer(modifier = Modifier.height(48.dp))

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
                                        val clipboard = context
                                            .getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager
                                        val pasted = clipboard.primaryClip
                                            ?.getItemAt(0)?.text?.toString() ?: ""
                                        if (pasted.isNotEmpty()) {
                                            url = pasted
                                            urlError = null
                                            media = null
                                            fullError = null
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = pasteDescription
                                    }
                                ) {
                                    Icon(
                                        imageVector = AppIcons.ContentPaste,
                                        contentDescription = null,
                                        tint = colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3,
                            enabled = !isSaving,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = IgPink,
                                focusedLabelColor = IgPink,
                                cursorColor = IgPink,
                            )
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        AnimatedVisibility(
                            visible = isStory,
                            enter = fadeIn(tween(200)),
                            exit = fadeOut(tween(200))
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 20.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = IgOrange.copy(alpha = 0.15f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
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
                                    trimmed.isBlank() -> urlError = context.getString(R.string.error_empty_url)
                                    !isValidInstagramUrl(trimmed) ->
                                        urlError = context.getString(R.string.error_invalid_url)
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                                            && !checkPermissions() -> requestPermissions()
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
                                                    ?: context.getString(R.string.error_generic)
                                                return@launch
                                            }
                                            fetched.getOrThrow().also { media = it }
                                        }
                                        val itemsToSave = items.filterIndexed { i, _ ->
                                            i !in deselectedIndices
                                        }
                                        hapticStart(context, settings.hapticsEnabled)
                                        isSaving = true
                                        var fellBackToDefaultFolder = false
                                        val dlResult = runCatching {
                                            withContext(Dispatchers.IO) {
                                                itemsToSave.forEachIndexed { i, item ->
                                                    try {
                                                        saveToDownloads(item.url, item.isVideo, i, context, settings.downloadTreeUri)
                                                    } catch (e: Exception) {
                                                        if (settings.downloadTreeUri == null) throw e
                                                        settings.downloadTreeUri = null
                                                        settings.downloadFolderName = AppSettings.DEFAULT_FOLDER_NAME
                                                        fellBackToDefaultFolder = true
                                                        saveToDownloads(item.url, item.isVideo, i, context, null)
                                                    }
                                                }
                                            }
                                        }
                                        isSaving = false
                                        if (fellBackToDefaultFolder) {
                                            onFolderAccessLost()
                                        }
                                        if (dlResult.isSuccess) {
                                            hapticComplete(context, settings.hapticsEnabled)
                                            downloadComplete = true
                                            if (fellBackToDefaultFolder) {
                                                fullError = context.getString(R.string.error_folder_access_lost)
                                            }
                                            delay(2500)
                                            downloadComplete = false
                                            media = null
                                            deselectedIndices = emptySet()
                                        } else {
                                            fullError = dlResult.exceptionOrNull()?.message
                                                ?: context.getString(R.string.error_download_failed)
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
                                    Icon(
                                        imageVector = AppIcons.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.download_label),
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
                                        }
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

            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = { showSettings = true },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.18f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(AppIcons.Settings, contentDescription = stringResource(R.string.settings_content_description))
                }
            }
        }
    }

    @Composable
    private fun isSystemInDarkMode(): Boolean =
        androidx.compose.foundation.isSystemInDarkTheme()

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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsDialog(
        settings: AppSettings,
        selectedFolderName: String,
        onChooseFolder: () -> Unit,
        onThemeChanged: (AppTheme) -> Unit,
        onDismiss: () -> Unit
    ) {
        var quality by remember { mutableStateOf(settings.quality) }
        var customWidth by remember { mutableIntStateOf(settings.customWidth) }
        var haptics by remember { mutableStateOf(settings.hapticsEnabled) }
        var theme by remember { mutableStateOf(settings.theme) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingsHeading(stringResource(R.string.download_location_heading))
                    Text(selectedFolderName, style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(
                        onClick = onChooseFolder,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(stringResource(R.string.choose_folder_button)) }
                    SettingsHeading(stringResource(R.string.download_quality_heading))
                    DownloadQuality.entries.forEach { option ->
                        SettingsRadio(
                            stringResource(option.labelRes),
                            stringResource(option.descriptionRes),
                            quality == option
                        ) {
                            quality = option
                            settings.quality = option
                        }
                    }
                    if (quality == DownloadQuality.CUSTOM) {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            OutlinedTextField(
                                value = stringResource(R.string.resolution_px_format, customWidth),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.resolution_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf(1080, 720, 640, 480, 320, 240).forEach { width ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.resolution_px_format, width)) },
                                        onClick = {
                                            customWidth = width
                                            settings.customWidth = width
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    SettingsHeading(stringResource(R.string.appearance_heading))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.haptic_feedback_label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.haptic_feedback_description), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = haptics,
                            onCheckedChange = {
                                haptics = it
                                settings.hapticsEnabled = it
                            }
                        )
                    }
                    AppTheme.entries.forEach { option ->
                        SettingsRadio(stringResource(option.labelRes), null, theme == option) {
                            theme = option
                            settings.theme = option
                            onThemeChanged(option)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done_button)) } }
        )
    }

    @Composable
    private fun SettingsHeading(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
    }

    @Composable
    private fun SettingsRadio(
        title: String,
        description: String?,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    @Composable
    private fun MediaThumbnail(
        item: MediaResult,
        isCarousel: Boolean = false,
        isSelected: Boolean = true,
        onToggleSelected: () -> Unit = {}
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
                    .clickable(enabled = isCarousel, onClick = onToggleSelected),
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

    // ── Download logic ─────────────────────────────────────────────

    private fun saveToDownloads(
        mediaUrl: String,
        isVideo: Boolean,
        index: Int,
        context: Context,
        downloadTreeUri: String?
    ) {
        val ts = System.currentTimeMillis() + index
        val fileName = if (isVideo) "instagram_video_$ts.mp4" else "instagram_image_$ts.jpg"
        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

        if (downloadTreeUri != null) {
            val treeUri = Uri.parse(downloadTreeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver, parent, mimeType, fileName
            ) ?: throw Exception(context.getString(R.string.error_create_file_in_folder))
            context.contentResolver.openOutputStream(fileUri)?.use { out ->
                InstagramDownloader.downloadToStream(mediaUrl, out)
            } ?: throw Exception(context.getString(R.string.error_write_to_folder))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InstaDownload")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw Exception(context.getString(R.string.error_create_file_in_downloads))
            resolver.openOutputStream(uri)?.use { out ->
                InstagramDownloader.downloadToStream(mediaUrl, out)
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            try {
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaDownload"
                ).apply { mkdirs() }
                val file = java.io.File(dir, fileName)
                InstagramDownloader.downloadToStream(mediaUrl, file.outputStream())
            } catch (e: SecurityException) {
                throw Exception(context.getString(R.string.error_storage_permission_lost), e)
            }
        }
    }

    private fun isValidInstagramUrl(url: String): Boolean =
        Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|tv)/[A-Za-z0-9_-]+"
        ).matcher(url).find() || isStoryUrl(url) || InstagramDownloader.isProfileUrl(url)

    private fun isStoryUrl(url: String): Boolean =
        Pattern.compile(
            "^https?://(www\\.)?instagram\\.com/stories/[A-Za-z0-9._]+"
        ).matcher(url).find()

    private fun checkPermissions(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // ── Haptics ────────────────────────────────────────────────────

    // Single crisp tick — download queued
    private fun hapticStart(context: Context, enabled: Boolean = true) {
        if (!enabled) return
        val v = vibrator(context)
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f)
                    .compose()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(40, 140))
        } else {
            @Suppress("DEPRECATION") v.vibrate(40)
        }
    }

    // Light tick then strong click — download finished
    private fun hapticComplete(context: Context, enabled: Boolean = true) {
        if (!enabled) return
        val v = vibrator(context)
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 60)
                    .compose()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 30, 60, 80),
                    intArrayOf(0, 80, 0, 220),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 30, 60, 80), -1)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
}

@Preview(showBackground = true)
@Composable
fun InstagramDownloaderPreview() {
    InstaDownloadTheme { }
}
