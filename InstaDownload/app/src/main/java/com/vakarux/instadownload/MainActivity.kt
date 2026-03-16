package com.vakarux.instadownload

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.core.net.toUri
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

    private var downloadId: Long = -1
    private var downloadCompleteCallback: (() -> Unit)? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) downloadCompleteCallback?.invoke()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, intentFilter)
        }

        setContent {
            InstaDownloadTheme {
                val sharedUrl = handleSharedIntent(intent)
                InstagramDownloaderScreen(initialUrl = sharedUrl)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(downloadReceiver) }
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
    fun InstagramDownloaderScreen(initialUrl: String = "") {
        var url by remember { mutableStateOf(initialUrl) }
        var isLoading by remember { mutableStateOf(false) }
        var urlError by remember { mutableStateOf<String?>(null) }
        var fullError by remember { mutableStateOf<String?>(null) }
        var downloadStarted by remember { mutableStateOf(false) }
        var downloadComplete by remember { mutableStateOf(false) }

        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val colorScheme = MaterialTheme.colorScheme

        DisposableEffect(Unit) {
            downloadCompleteCallback = {
                coroutineScope.launch {
                    downloadComplete = true
                    hapticComplete(context)
                    delay(2500)
                    downloadStarted = false
                    downloadComplete = false
                }
            }
            onDispose { downloadCompleteCallback = null }
        }

        val igGradient = Brush.verticalGradient(
            colors = if (isSystemInDarkMode()) {
                listOf(IgPurpleDark, IgPinkDark, IgOrangeDark)
            } else {
                listOf(IgPurple, IgPink, IgOrange)
            }
        )

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
                            },
                            label = { Text("Instagram URL") },
                            placeholder = { Text("https://www.instagram.com/reel/…") },
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp),
                            singleLine = false,
                            maxLines = 3,
                            enabled = !isLoading,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = IgPink,
                                focusedLabelColor = IgPink,
                                cursorColor = IgPink,
                            )
                        )

                        Button(
                            onClick = {
                                when {
                                    url.isBlank() -> urlError = "Please enter a URL"
                                    !isValidInstagramUrl(url.trim()) ->
                                        urlError = "Not a valid Instagram post or reel URL"
                                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                                            && !checkPermissions() -> {
                                        requestPermissions()
                                    }
                                    else -> coroutineScope.launch {
                                        isLoading = true
                                        urlError = null
                                        fullError = null
                                        downloadStarted = false
                                        downloadComplete = false
                                        val result = runCatching {
                                            downloadInstagramVideo(url.trim(), context)
                                        }
                                        isLoading = false
                                        if (result.isSuccess) {
                                            hapticStart(context)
                                            downloadStarted = true
                                        } else {
                                            fullError = result.exceptionOrNull()?.message ?: "Something went wrong"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = !isLoading,
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
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Fetching…",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            } else {
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

                Spacer(modifier = Modifier.height(16.dp))

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
                                TextButton(onClick = { fullError = null }) {
                                    Text(
                                        "Dismiss",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = colorScheme.onErrorContainer
                                        )
                                    )
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
                        }
                    }
                }

                // ── Success card ──────────────────────────────────
                AnimatedVisibility(
                    visible = downloadStarted,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200))
                ) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = null,
                                tint = colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (downloadComplete) "Download complete!" else "Downloading to Downloads folder…",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium
                                )
                            )
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
            onClick = { uriHandler.openUri("https://github.com/Vakarux12") },
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

    // ── Download logic ─────────────────────────────────────────────

    private suspend fun downloadInstagramVideo(url: String, context: Context) {
        val result = withContext(Dispatchers.IO) {
            InstagramDownloader.getMediaUrl(url)
        }
        downloadId = startDownload(result.url, result.isVideo, context)
    }

    private fun startDownload(mediaUrl: String, isVideo: Boolean, context: Context): Long {
        val ts = System.currentTimeMillis()
        val fileName = if (isVideo) "instagram_video_$ts.mp4" else "instagram_image_$ts.jpg"
        val title = if (isVideo) "Instagram Video" else "Instagram Image"
        val request = DownloadManager.Request(mediaUrl.toUri()).apply {
            setTitle(title)
            setDescription("Downloading…")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            addRequestHeader("User-Agent", "Mozilla/5.0 (Android)")
        }
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
            .enqueue(request)
    }

    private fun isValidInstagramUrl(url: String): Boolean =
        Pattern.compile(
            "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|tv)/[A-Za-z0-9_-]+/?.*"
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
