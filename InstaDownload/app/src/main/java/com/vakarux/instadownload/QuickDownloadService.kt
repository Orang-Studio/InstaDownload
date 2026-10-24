package com.vakarux.instadownload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active = 0
    private var batchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_BATCH -> batchJob?.cancel()
            ACTION_BATCH -> startBatch(
                intent.getStringArrayListExtra(EXTRA_CODES).orEmpty(),
                intent.getIntArrayExtra(EXTRA_COUNTS) ?: IntArray(0),
                intent.getStringExtra(EXTRA_TREE_URI),
                startId
            )
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            if (active == 0) stopSelf()
            return START_NOT_STICKY
        }

        active++
        startForeground(NOTIFICATION_ID, buildNotification(
            title = getString(R.string.quick_download_in_progress_title),
            text = getString(R.string.quick_download_in_progress_text),
            ongoing = true
        ))

        serviceScope.launch {
            val result = runCatching { download(url) }
            withContext(Dispatchers.Main) {
                val notification = result.fold(
                    onSuccess = { usedDefaultFolder ->
                        buildNotification(
                            title = getString(R.string.quick_download_complete_title),
                            text = if (usedDefaultFolder) {
                                getString(R.string.quick_download_complete_fallback)
                            } else {
                                getString(R.string.quick_download_complete_text)
                            },
                            ongoing = false,
                            opensLibrary = true
                        )
                    },
                    onFailure = { error ->
                        buildNotification(
                            title = getString(R.string.quick_download_failed_title),
                            text = error.message ?: getString(R.string.error_download_failed),
                            ongoing = false,
                            opensLibrary = false
                        )
                    }
                )
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(startId, notification)
                }
                finishOne()
            }
        }

        return START_NOT_STICKY
    }

    private fun finishOne() {
        if (--active == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startBatch(codes: List<String>, counts: IntArray, treeUri: String?, startId: Int) {
        if (batchJob?.isActive == true) return
        active++
        _batch.value = BatchState(total = counts.sum())
        startForeground(NOTIFICATION_ID, batchNotification(_batch.value!!))
        batchJob = serviceScope.launch {
            val settings = AppSettings(this@QuickDownloadService)
            val update = { change: (BatchState) -> BatchState ->
                _batch.value = change(_batch.value!!)
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, batchNotification(_batch.value!!))
                }
            }
            try {
                codes.forEachIndexed { i, code ->
                    ensureActive()
                    val items = runCatching {
                        InstagramDownloader.getMediaItems("https://www.instagram.com/p/$code/", settings.targetWidth())
                    }.getOrElse {
                        update { it.copy(failed = it.failed + counts[i]) }
                        return@forEachIndexed
                    }
                    items.forEach { item ->
                        ensureActive()
                        runCatching {
                            saveToDownloads(item, this@QuickDownloadService, settings, treeUri ?: settings.downloadTreeUri)
                        }.fold(
                            onSuccess = { out ->
                                update { it.copy(saved = it.saved + 1, fellBack = it.fellBack || out.fellBackToDefaultFolder) }
                            },
                            onFailure = { update { it.copy(failed = it.failed + 1) } }
                        )
                    }
                }
            } finally {
                val state = _batch.value!!.copy(running = false)
                _batch.value = state
                withContext(Dispatchers.Main + NonCancellable) {
                    runCatching {
                        getSystemService(NotificationManager::class.java).notify(startId, buildNotification(
                            title = getString(R.string.quick_download_complete_title),
                            text = if (state.failed == 0) getString(R.string.profile_saved_format, state.saved)
                                else getString(R.string.profile_saved_failed_format, state.saved, state.failed),
                            ongoing = false,
                            opensLibrary = true
                        ))
                    }
                    finishOne()
                }
            }
        }
    }

    private fun batchNotification(state: BatchState): Notification {
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, QuickDownloadService::class.java).setAction(ACTION_STOP_BATCH),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(getString(R.string.quick_download_in_progress_title))
            .setContentText(getString(R.string.batch_progress_format, state.done, state.total))
            .setContentIntent(mainActivityPendingIntent(false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(state.total, state.done, false)
            .addAction(0, getString(R.string.stop_loading_label), stopIntent)
            .addExtras(Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                putString("android.shortCriticalText", "${state.done}/${state.total}")
            })
            .build()
    }

    private fun download(url: String): Boolean {
        val settings = AppSettings(this)
        val items = InstagramDownloader.getMediaItems(url, settings.targetWidth())
        if (items.isEmpty()) throw Exception(getString(R.string.error_download_failed))
        return items.map { saveToDownloads(it, this, settings).fellBackToDefaultFolder }
            .any { it }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progress = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.quick_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.quick_download_channel_description)
            }
            val results = NotificationChannel(
                RESULT_CHANNEL_ID,
                getString(R.string.quick_download_results_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.quick_download_results_channel_description)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannels(listOf(progress, results))
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean,
        opensLibrary: Boolean = false
    ) =
        NotificationCompat.Builder(this, if (ongoing) CHANNEL_ID else RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(mainActivityPendingIntent(opensLibrary))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply {
                if (ongoing) setProgress(0, 0, true)
            }
            .build()

    private fun mainActivityPendingIntent(openLibrary: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_LIBRARY, openLibrary)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, if (openLibrary) 1 else 0, intent, flags)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    data class BatchState(
        val total: Int,
        val saved: Int = 0,
        val failed: Int = 0,
        val fellBack: Boolean = false,
        val running: Boolean = true,
    ) {
        val done: Int get() = saved + failed
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.vakarux.instadownload.action.QUICK_DOWNLOAD"
        private const val ACTION_BATCH = "com.vakarux.instadownload.action.BATCH"
        private const val ACTION_STOP_BATCH = "com.vakarux.instadownload.action.STOP_BATCH"
        private const val EXTRA_CODES = "com.vakarux.instadownload.extra.CODES"
        private const val EXTRA_COUNTS = "com.vakarux.instadownload.extra.COUNTS"
        private const val EXTRA_TREE_URI = "com.vakarux.instadownload.extra.TREE_URI"

        private val _batch = MutableStateFlow<BatchState?>(null)
        val batch: StateFlow<BatchState?> = _batch

        fun startBatch(context: Context, posts: List<ProfilePost>, treeUri: String?) {
            val intent = Intent(context, QuickDownloadService::class.java)
                .setAction(ACTION_BATCH)
                .putStringArrayListExtra(EXTRA_CODES, ArrayList(posts.map { it.code }))
                .putExtra(EXTRA_COUNTS, posts.map { it.mediaCount }.toIntArray())
                .putExtra(EXTRA_TREE_URI, treeUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stopBatch(context: Context) {
            context.startService(Intent(context, QuickDownloadService::class.java).setAction(ACTION_STOP_BATCH))
        }
        const val EXTRA_URL = "com.vakarux.instadownload.extra.URL"

        private const val CHANNEL_ID = "quick_downloads"
        private const val RESULT_CHANNEL_ID = "quick_download_results"
        private const val NOTIFICATION_ID = 2001
    }
}
