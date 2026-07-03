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
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
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

// Instagram brand gradient colors
private val IgPurple = Color(0xFF833AB4)
private val IgPink   = Color(0xFFE1306C)
private val IgOrange = Color(0xFFF77737)

// Dark equivalents (desaturated per MD3 dark mode guidance)
private val IgPurpleDark = Color(0xFF2D1B2E)
private val IgPinkDark   = Color(0xFF4A1428)
private val IgOrangeDark = Color(0xFF3D1A0A)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* permission result handled inline */ }

    private val sessionStore by lazy { SessionStore(this) }
    private val loggedIn = mutableStateOf(false)

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loggedIn.value = sessionStore.isLoggedIn }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loggedIn.value = sessionStore.isLoggedIn

        setContent {
            InstaDownloadTheme {
                val sharedUrl = handleSharedIntent(intent)
                InstagramDownloaderScreen(
                    initialUrl = sharedUrl,
                    isLoggedIn = loggedIn.value,
                    onLoginClick = { loginLauncher.launch(Intent(this, LoginActivity::class.java)) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
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
        isLoggedIn: Boolean = false,
        onLoginClick: () -> Unit = {}
    ) {
        var url by remember { mutableStateOf(initialUrl) }
        var isLoading by remember { mutableStateOf(false) }
        var isSaving by remember { mutableStateOf(false) }
        var urlError by remember { mutableStateOf<String?>(null) }
        var fullError by remember { mutableStateOf<String?>(null) }
        var media by remember { mutableStateOf<List<MediaResult>?>(null) }
        var downloadComplete by remember { mutableStateOf(false) }

        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val colorScheme = MaterialTheme.colorScheme
        val uriHandler = LocalUriHandler.current

        val isStory = isStoryUrl(url.trim())
        val needsLogin = isStory && !isLoggedIn

        val igGradient = Brush.verticalGradient(
            colors = if (isSystemInDarkMode()) {
                listOf(IgPurpleDark, IgPinkDark, IgOrangeDark)
            } else {
                listOf(IgPurple, IgPink, IgOrange)
            }
        )

        LaunchedEffect(url) {
            val trimmed = url.trim()
            if (trimmed.isBlank() || !isValidInstagramUrl(trimmed)) {
                media = null
                return@LaunchedEffect
            }
            delay(350)
            isLoading = true
            urlError = null
            fullError = null
            media = null
            downloadComplete = false
            val items = runCatching {
                withContext(Dispatchers.IO) {
                    InstagramDownloader.getMediaItems(trimmed, sessionStore.sessionId)
                }
            }
            isLoading = false
            if (items.isFailure) {
                fullError = items.exceptionOrNull()?.message ?: "Something went wrong"
                return@LaunchedEffect
            }
            hapticStart(context)
            media = items.getOrThrow()
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
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "InstaDownload",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Save reels & posts to your device",
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

                        OutlinedTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                if (urlError != null) urlError = null
                                media = null
                                fullError = null
                            },
                            label = { Text("Instagram URL") },
                            placeholder = { Text("https://www.instagram.com/reel/...") },
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
                                        contentDescription = "Paste from clipboard"
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentPaste,
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
                            visible = needsLogin,
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
                                        "Stories require a login",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = IgOrange,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        "Only logged-in accounts can see Stories on Instagram. " +
                                            "You sign in here in the app, so your details never leave " +
                                            "your device. Reels and posts download as usual, no login.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        if (needsLogin) {
                            Button(
                                onClick = onLoginClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = IgOrange,
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 4.dp,
                                    pressedElevation = 2.dp
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Login,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Log in to Instagram",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        } else Button(
                            onClick = {
                                val trimmed = url.trim()
                                when {
                                    trimmed.isBlank() -> urlError = "Please enter a URL"
                                    !isValidInstagramUrl(trimmed) && !isStoryUrl(trimmed) ->
                                        urlError = "Not a valid Instagram post, reel, or story URL"
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                                            && !checkPermissions() -> requestPermissions()
                                    else -> coroutineScope.launch {
                                        fullError = null
                                        val items = media ?: run {
                                            isLoading = true
                                            val fetched = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    InstagramDownloader.getMediaItems(trimmed, sessionStore.sessionId)
                                                }
                                            }
                                            isLoading = false
                                            if (fetched.isFailure) {
                                                fullError = fetched.exceptionOrNull()?.message ?: "Something went wrong"
                                                return@launch
                                            }
                                            fetched.getOrThrow().also { media = it }
                                        }
                                        hapticStart(context)
                                        isSaving = true
                                        val dlResult = runCatching {
                                            withContext(Dispatchers.IO) {
                                                items.forEachIndexed { i, item ->
                                                    saveToDownloads(item.url, item.isVideo, i, context)
                                                }
                                            }
                                        }
                                        isSaving = false
                                        if (dlResult.isSuccess) {
                                            hapticComplete(context)
                                            downloadComplete = true
                                            delay(2500)
                                            downloadComplete = false
                                            media = null
                                        } else {
                                            fullError = dlResult.exceptionOrNull()?.message ?: "Download failed"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = !isSaving,
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
                                        "Saving…",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                downloadComplete -> {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Saved!",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                else -> {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Download",
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
                                if (items.size > 1) "${items.size} items" else "Preview",
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
                                items.forEach { item -> MediaThumbnail(item) }
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
                                Text(
                                    "Error",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Row {
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Error", fullError)
                                        clipboard.setPrimaryClip(clip)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "Copy error",
                                            tint = colorScheme.onErrorContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    TextButton(onClick = { fullError = null }) {
                                        Text(
                                            "Dismiss",
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
                                "Instagram may have changed — updating to the latest version usually fixes this.",
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
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Update to latest release",
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
    }

    @Composable
    private fun isSystemInDarkMode(): Boolean =
        androidx.compose.foundation.isSystemInDarkTheme()

    @Composable
    fun GitHubCredit() {
        val uriHandler = LocalUriHandler.current

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
                    contentDescription = "GitHub",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Made by Vakarux",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = Color.White.copy(alpha = 0.85f)
                )
            )
        }
    }

    @Composable
    private fun MediaThumbnail(item: MediaResult) {
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

        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = if (item.isVideo) "Video preview" else "Image preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                previewUrl == null || loadFailed -> Icon(
                    imageVector = if (item.isVideo) Icons.Filled.Movie else Icons.Filled.Image,
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
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    // ── Download logic ─────────────────────────────────────────────

    private fun saveToDownloads(mediaUrl: String, isVideo: Boolean, index: Int, context: Context) {
        val ts = System.currentTimeMillis() + index
        val fileName = if (isVideo) "instagram_video_$ts.mp4" else "instagram_image_$ts.jpg"
        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create file in Downloads")
            resolver.openOutputStream(uri)?.use { out ->
                InstagramDownloader.downloadToStream(mediaUrl, out)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(dir, fileName)
            InstagramDownloader.downloadToStream(mediaUrl, file.outputStream())
        }
    }

    private fun isValidInstagramUrl(url: String): Boolean =
        Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|tv)/[A-Za-z0-9_-]+"
        ).matcher(url).find()

    private fun isStoryUrl(url: String): Boolean =
        Pattern.compile(
            "^https?://(www\\.)?instagram\\.com/stories/[A-Za-z0-9._]+/?.*"
        ).matcher(url).matches()

    private fun checkPermissions(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // ── Haptics ────────────────────────────────────────────────────

    // Single crisp tick — download queued
    private fun hapticStart(context: Context) {
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
    private fun hapticComplete(context: Context) {
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
