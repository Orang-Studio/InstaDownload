package com.vakarux.instadownload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(
            title = getString(R.string.quick_download_in_progress_title),
            text = getString(R.string.quick_download_in_progress_text),
            ongoing = true
        ))

        serviceScope.launch {
            val result = runCatching { download(url) }
            withContext(Dispatchers.Main) {
                val notification = result.fold(
                    onSuccess = { outcome ->
                        buildNotification(
                            title = getString(R.string.quick_download_complete_title),
                            text = if (outcome.usedDefaultFolder) {
                                getString(R.string.quick_download_complete_fallback)
                            } else {
                                getString(R.string.quick_download_complete_text)
                            },
                            ongoing = false
                        )
                    },
                    onFailure = { error ->
                        buildNotification(
                            title = getString(R.string.quick_download_failed_title),
                            text = error.message ?: getString(R.string.error_download_failed),
                            ongoing = false
                        )
                    }
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification)
                }
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun download(url: String): DownloadOutcome {
        val settings = AppSettings(this)
        val items = InstagramDownloader.getMediaItems(url, Int.MAX_VALUE)
        if (items.isEmpty()) throw Exception(getString(R.string.error_download_failed))

        var usedDefaultFolder = false
        items.forEach { item ->
            try {
                saveToDownloads(item, this, settings.downloadTreeUri)
            } catch (error: Exception) {
                if (settings.downloadTreeUri == null) throw error
                settings.downloadTreeUri = null
                settings.downloadFolderName = AppSettings.DEFAULT_FOLDER_NAME
                usedDefaultFolder = true
                saveToDownloads(item, this, null)
            }
        }
        return DownloadOutcome(usedDefaultFolder)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.quick_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.quick_download_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) setProgress(0, 0, true)
            }
            .build()

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class DownloadOutcome(val usedDefaultFolder: Boolean)

    companion object {
        const val ACTION_DOWNLOAD = "com.vakarux.instadownload.action.QUICK_DOWNLOAD"
        const val EXTRA_URL = "com.vakarux.instadownload.extra.URL"

        private const val CHANNEL_ID = "quick_downloads"
        private const val NOTIFICATION_ID = 2001
    }
}
