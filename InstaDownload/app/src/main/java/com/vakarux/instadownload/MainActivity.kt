package com.vakarux.instadownload

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import androidx.core.net.toUri
import com.chaquo.python.Python

class MainActivity : ComponentActivity() {


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Storage permission required for downloads", Toast.LENGTH_SHORT).show()
        }
    }

    private var downloadId: Long = -1

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    Toast.makeText(context, "Finished downloading!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register download completion receiver with proper export flag
        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        if (Build.VERSION.SDK_INT >= 33) { // API 33 (Android 13)
            registerReceiver(downloadReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, intentFilter)
        }

        setContent {
            InstaDownloadTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val sharedUrl = handleSharedIntent(intent)
                    InstagramDownloaderScreen(initialUrl = sharedUrl)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private fun handleSharedIntent(intent: Intent): String {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    if (isValidInstagramUrl(sharedText)) sharedText else ""
                } else ""
            }
            else -> ""
        }
    }

    @Composable
    fun InstagramDownloaderScreen(initialUrl: String = "") {
        var url by remember { mutableStateOf(initialUrl) }
        var isLoading by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val isDarkTheme = isSystemInDarkTheme()

        // Load server statuses on first load

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = if (isDarkTheme) {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF1A1A1A), // Dark gray
                                Color(0xFF2D1B2E), // Dark purple
                                Color(0xFF4A1428), // Dark red
                                Color(0xFF3D1A0A)  // Dark orange
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF833AB4),
                                Color(0xFFE1306C),
                                Color(0xFFFD1D1D),
                                Color(0xFFF77737)
                            )
                        )
                    }
                )
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                // App Icon + Title
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.download),
                            contentDescription = "Download",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "InstaDownloader",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Save your favorite content",
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }



                // Input Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkTheme) {
                            Color(0xFF2A2A2A).copy(alpha = 0.95f) // Dark card background
                        } else {
                            Color.White.copy(alpha = 0.95f) // Light card background
                        }
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = {
                                Text(
                                    "Paste Instagram URL",
                                    color = if (isDarkTheme) Color(0xFFB0B0B0) else Color(0xFF666666)
                                )
                            },
                            placeholder = {
                                Text(
                                    "https://www.instagram.com/p/...",
                                    color = if (isDarkTheme) Color(0xFF808080) else Color(0xFF999999)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp),
                            singleLine = false,
                            maxLines = 3,
                            enabled = !isLoading,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFE91E63),
                                focusedLabelColor = Color(0xFFE91E63),
                                cursorColor = Color(0xFFE91E63),
                                focusedTextColor = if (isDarkTheme) Color.White else Color.Black,
                                unfocusedTextColor = if (isDarkTheme) Color(0xFFE0E0E0) else Color.Black,
                                unfocusedBorderColor = if (isDarkTheme) Color(0xFF555555) else Color(0xFFDDDDDD)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                if (url.isEmpty()) {
                                    Toast.makeText(context, "Please enter a URL", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                if (!isValidInstagramUrl(url)) {
                                    Toast.makeText(context, "Invalid Instagram URL", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !checkPermissions()) {
                                    requestPermissions()
                                    return@Button
                                }

                                coroutineScope.launch {
                                    isLoading = true
                                    downloadInstagramVideo(url, context)
                                    isLoading = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63)),
                            enabled = !isLoading,
                            shape = RoundedCornerShape(16.dp),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 8.dp
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Processing...", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            } else {
                                Text("Download Video", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Spacer(modifier = Modifier.height(16.dp))

                // GitHub Credit
                GitHubCredit()
            }

            // Server Status Sheet

        }
    }





    @Composable
    fun GitHubCredit() {
        val uriHandler = LocalUriHandler.current

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Made by Vakarux",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { uriHandler.openUri("https://github.com/Vakarux12") },
                modifier = Modifier.size(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.github),
                        contentDescription = "GitHub",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }



    private suspend fun downloadInstagramVideo(url: String, context: Context) {
        withContext(Dispatchers.IO) {
            try {
                // Call Python: direct_video_url(url, username, password)
                val py = Python.getInstance()
                val mod = py.getModule("ig_dl")
                val pyResult = mod.callAttr("direct_video_url", url, null, null)
                val videoUrl = pyResult.toString().takeIf { it != "None" && it.startsWith("http") }

                withContext(Dispatchers.Main) {
                    if (videoUrl != null) {
                        downloadId = startDownload(videoUrl, context)
                        Toast.makeText(context, "Download started!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Post has no video (images only).", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun startDownload(videoUrl: String, context: Context): Long {
        val fileName = "instagram_video_${System.currentTimeMillis()}.mp4"
        val request = DownloadManager.Request(videoUrl.toUri()).apply {
            setTitle("Instagram Video")
            setDescription("Downloading...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            addRequestHeader("User-Agent", "Mozilla/5.0 (Android)")
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    private fun isValidInstagramUrl(url: String): Boolean {
        val pattern = "^https?://(www\\.)?(instagram\\.com|instagr\\.am)/(p|reel|tv)/[A-Za-z0-9_-]+/?.*"
        return Pattern.compile(pattern).matcher(url).matches()
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

@Preview(showBackground = true)
@Composable
fun InstagramDownloaderPreview() {
    InstaDownloadTheme {
        // Preview content would go here
    }
}