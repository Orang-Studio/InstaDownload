package com.vakarux.instadownload

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class QuickDownloadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let { url ->
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

        finish()
    }
}
