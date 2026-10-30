package com.vakarux.instadownload

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.vakarux.instadownload.data.AppSettings
import com.vakarux.instadownload.model.AppTheme
import com.vakarux.instadownload.ui.AppIcons
import com.vakarux.instadownload.ui.screens.DownloaderScreen
import com.vakarux.instadownload.ui.screens.FileManagerScreen
import com.vakarux.instadownload.ui.theme.InstaDownloadTheme
import com.vakarux.instadownload.util.Haptics
import com.vakarux.instadownload.util.UrlUtils
import me.ibrahimsn.smoothbottombar.SmoothBarItem
import me.ibrahimsn.smoothbottombar.SmoothBottomBar

class MainActivity : ComponentActivity() {

    private val appSettings by lazy { AppSettings(this) }
    private val selectedFolderName = mutableStateOf(AppSettings.DEFAULT_FOLDER_NAME)
    private val openFileManagerState = mutableStateOf(false)
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
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        selectedFolderName.value = appSettings.downloadFolderName
        sharedUrl.value = SharedUrl(handleSharedIntent(intent))
        if (savedInstanceState == null) {
            openFileManagerState.value = intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) ||
                intent.getBooleanExtra("open_library", false)
        }

        setContent {
            val settings = appSettings
            var selectedTheme by remember { mutableStateOf(settings.theme) }
            val useDarkTheme = when (selectedTheme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            InstaDownloadTheme(darkTheme = useDarkTheme) {
                var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

                BackHandler(enabled = selectedTabIndex == 1) {
                    selectedTabIndex = 0
                }
                var hasUnreadDownloads by rememberSaveable { mutableStateOf(false) }

                val view = LocalView.current
                val activity = view.context as? ComponentActivity
                val isDownloader = selectedTabIndex == 0
                val needDarkStatusIcons = !isDownloader && !useDarkTheme
                val needDarkNavIcons = !useDarkTheme

                LaunchedEffect(selectedTabIndex, useDarkTheme) {
                    if (activity != null) {
                        activity.enableEdgeToEdge(
                            statusBarStyle = if (needDarkStatusIcons) {
                                androidx.activity.SystemBarStyle.light(
                                    android.graphics.Color.TRANSPARENT,
                                    android.graphics.Color.TRANSPARENT
                                )
                            } else {
                                androidx.activity.SystemBarStyle.dark(
                                    android.graphics.Color.TRANSPARENT
                                )
                            },
                            navigationBarStyle = if (needDarkNavIcons) {
                                androidx.activity.SystemBarStyle.light(
                                    android.graphics.Color.TRANSPARENT,
                                    android.graphics.Color.TRANSPARENT
                                )
                            } else {
                                androidx.activity.SystemBarStyle.dark(
                                    android.graphics.Color.TRANSPARENT
                                )
                            }
                        )
                        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        insetsController.isAppearanceLightStatusBars = needDarkStatusIcons
                        insetsController.isAppearanceLightNavigationBars = needDarkNavIcons
                    }
                }

                LaunchedEffect(openFileManagerState.value) {
                    if (openFileManagerState.value) {
                        selectedTabIndex = 1
                        hasUnreadDownloads = false
                        openFileManagerState.value = false
                    }
                }

                val bottomBarItems = listOf(
                    SmoothBarItem(
                        icon = rememberVectorPainter(AppIcons.Download),
                        label = stringResource(R.string.tab_downloader),
                        contentDescription = "Downloader Section",
                        hasBadge = false
                    ),
                    SmoothBarItem(
                        icon = rememberVectorPainter(AppIcons.Folder),
                        label = stringResource(R.string.tab_file_manager),
                        contentDescription = "File Manager Section",
                        hasBadge = hasUnreadDownloads
                    )
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        SmoothBottomBar(
                            items = bottomBarItems,
                            selectedIndex = selectedTabIndex,
                            onItemSelected = { newIndex ->
                                selectedTabIndex = newIndex
                                if (newIndex == 1) {
                                    hasUnreadDownloads = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            indicatorRadius = 14.dp,
                            itemTextColor = MaterialTheme.colorScheme.primary,
                            itemIconTintActive = MaterialTheme.colorScheme.primary
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        Crossfade(
                            targetState = selectedTabIndex,
                            label = "BottomBarSectionTransition"
                        ) { tabIndex ->
                            when (tabIndex) {
                                0 -> DownloaderScreen(
                                    initialUrl = sharedUrl.value.url,
                                    initialUrlKey = sharedUrl.value,
                                    useDarkTheme = useDarkTheme,
                                    settings = settings,
                                    selectedFolderName = selectedFolderName.value,
                                    onChooseFolder = { folderPickerLauncher.launch(null) },
                                    onThemeChanged = { selectedTheme = it },
                                    onFolderAccessLost = { selectedFolderName.value = AppSettings.DEFAULT_FOLDER_NAME },
                                    onOpenLibrary = {
                                        selectedTabIndex = 1
                                        hasUnreadDownloads = false
                                    },
                                    onDownloadsSaved = {
                                        hasUnreadDownloads = true
                                    },
                                    onCheckPermissions = { checkPermissions() },
                                    onRequestPermissions = { requestPermissions() }
                                )
                                1 -> FileManagerScreen(
                                    settings = settings,
                                    onChooseFolder = { folderPickerLauncher.launch(null) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl.value = SharedUrl(handleSharedIntent(intent))
        if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) || intent.getBooleanExtra("open_library", false)) {
            openFileManagerState.value = true
        }
    }

    private fun handleSharedIntent(intent: Intent): String {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (UrlUtils.isValidInstagramUrl(text)) return text
        }
        return ""
    }

    @Composable
    fun InstagramDownloaderScreen(
        initialUrl: String = "",
        initialUrlKey: Any = initialUrl,
        useDarkTheme: Boolean = isSystemInDarkTheme(),
        settings: AppSettings = appSettings,
        selectedFolderName: String = settings.downloadFolderName,
        onChooseFolder: () -> Unit = {},
        onThemeChanged: (AppTheme) -> Unit = {},
        onFolderAccessLost: () -> Unit = {},
        onOpenLibrary: () -> Unit = {},
        onDownloadsSaved: () -> Unit = {}
    ) {
        DownloaderScreen(
            initialUrl = initialUrl,
            initialUrlKey = initialUrlKey,
            useDarkTheme = useDarkTheme,
            settings = settings,
            selectedFolderName = selectedFolderName,
            onChooseFolder = onChooseFolder,
            onThemeChanged = onThemeChanged,
            onFolderAccessLost = onFolderAccessLost,
            onOpenLibrary = onOpenLibrary,
            onDownloadsSaved = onDownloadsSaved,
            onCheckPermissions = { checkPermissions() },
            onRequestPermissions = { requestPermissions() }
        )
    }

    private fun checkPermissions(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hapticStart(context: Context, enabled: Boolean = true) =
        Haptics.hapticStart(context, enabled)

    private fun hapticComplete(context: Context, enabled: Boolean = true) =
        Haptics.hapticComplete(context, enabled)

    private fun vibrator(context: Context): Vibrator =
        Haptics.vibrator(context)

    companion object {
        const val EXTRA_OPEN_LIBRARY = "com.vakarux.instadownload.extra.OPEN_LIBRARY"
    }
}

@Preview(showBackground = true)
@Composable
fun InstagramDownloaderPreview() {
    InstaDownloadTheme { }
}
