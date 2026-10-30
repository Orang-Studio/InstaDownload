package com.vakarux.instadownload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.vakarux.instadownload.service.QuickDownloadService

class QuickDownloadActivity : Activity() {

    private var pendingSharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (sharedText.isNullOrBlank()) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSharedText = sharedText
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        } else {
            dispatchQuickDownload(sharedText)
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingSharedText?.let { dispatchQuickDownload(it) }
        finish()
    }

    private fun dispatchQuickDownload(url: String) {
        val serviceIntent = Intent(this, QuickDownloadService::class.java).apply {
            action = QuickDownloadService.ACTION_DOWNLOAD
            putExtra(QuickDownloadService.EXTRA_URL, url)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
