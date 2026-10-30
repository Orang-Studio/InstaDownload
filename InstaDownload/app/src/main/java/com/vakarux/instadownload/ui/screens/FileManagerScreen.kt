package com.vakarux.instadownload.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.vakarux.instadownload.AppSettings
import com.vakarux.instadownload.DownloadHistory
import com.vakarux.instadownload.isMediaUriAccessible
import com.vakarux.instadownload.MediaPreview
import com.vakarux.instadownload.R
import com.vakarux.instadownload.model.DownloadKind
import com.vakarux.instadownload.model.DownloadRecord
import com.vakarux.instadownload.model.kind
import com.vakarux.instadownload.ui.AppIcons
import com.vakarux.instadownload.ui.components.DownloadItemCard
import com.vakarux.instadownload.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    settings: AppSettings,
    onChooseFolder: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(DownloadHistory.load(context)) }
    var selectedKind by remember { mutableStateOf(DownloadKind.ALL) }
    var previewRecord by remember { mutableStateOf<DownloadRecord?>(null) }
    var fullscreen by remember { mutableStateOf(false) }

    // Reload when screen appears
    LaunchedEffect(Unit) {
        records = DownloadHistory.load(context)
    }

    val visibleRecords by remember(records, selectedKind) {
        derivedStateOf {
            records.filter { record ->
                selectedKind == DownloadKind.ALL || record.kind == selectedKind
            }
        }
    }

    BackHandler(enabled = previewRecord != null) {
        if (fullscreen) {
            fullscreen = false
        } else {
            previewRecord = null
        }
    }

    val isDark = isAppInDarkTheme()
    val topSurfaceColor = if (isDark) MaterialTheme.colorScheme.surface else Color.White
    val screenBg = if (isDark) MaterialTheme.colorScheme.background else Color(0xFFF7F7FA)



    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
        // Section Header showing active folder, count, and actions
        Surface(
            color = topSurfaceColor,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tab_file_manager),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${settings.downloadFolderName} • ${visibleRecords.size} items",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    // Prune broken/missing files from history
                                    val allRecords = DownloadHistory.load(context)
                                    val accessible = allRecords.filter { isMediaUriAccessible(context, it.uri) }
                                    withContext(Dispatchers.Main) {
                                        records = accessible
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = AppIcons.Refresh,
                                contentDescription = stringResource(R.string.refresh_downloads_label)
                            )
                        }

                        if (onChooseFolder != null) {
                            TextButton(onClick = onChooseFolder) {
                                Text(stringResource(R.string.choose_folder_button))
                            }
                        }
                    }
                }
            }
        }

        // Category Filter Tabs using PrimaryScrollableTabRow
        val kinds = DownloadKind.entries
        PrimaryScrollableTabRow(
            selectedTabIndex = kinds.indexOf(selectedKind),
            containerColor = topSurfaceColor,
            edgePadding = 16.dp
        ) {
            for (kind in kinds) {
                Tab(
                    selected = selectedKind == kind,
                    onClick = { selectedKind = kind },
                    text = { Text(stringResource(kind.labelRes)) }
                )
            }
        }

        if (visibleRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
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
                        text = if (records.isEmpty()) stringResource(R.string.downloads_empty)
                        else stringResource(R.string.downloads_empty_kind, stringResource(selectedKind.labelRes)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    if (records.isEmpty()) {
                        Text(
                            text = stringResource(R.string.downloads_empty_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = visibleRecords,
                    key = { "${it.uri}_${it.downloadedAt}" }
                ) { record ->
                    DownloadItemCard(
                        record = record,
                        onPreview = { previewRecord = record },
                        onDeleted = {
                            scope.launch(Dispatchers.IO) {
                                DownloadHistory.remove(context, record)
                                val updated = DownloadHistory.load(context)
                                withContext(Dispatchers.Main) {
                                    records = updated
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // Fullscreen / in-app media preview overlay
    previewRecord?.let { record ->
        Dialog(
            onDismissRequest = {
                if (fullscreen) fullscreen = false
                else previewRecord = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (fullscreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
                color = if (fullscreen) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.background
            ) {
                Column {
                    if (!fullscreen) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.preview_label),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 20.dp, top = 16.dp, bottom = 12.dp)
                            )
                            TextButton(
                                onClick = { previewRecord = null },
                                modifier = Modifier.padding(top = 8.dp, end = 12.dp)
                            ) {
                                Text(stringResource(R.string.back_label))
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .then(if (fullscreen) Modifier else Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
                    ) {
                        MediaPreview(
                            record = record,
                            fullscreen = fullscreen,
                            onFullscreenChange = { fullscreen = it }
                        )
                    }
                }
            }
        }
    }
}

