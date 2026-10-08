package com.vakarux.instadownload

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class QuickDownloadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        extractInstagramUrl(intent)?.let { url ->
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

    private fun extractInstagramUrl(intent: Intent?): String? {
        val sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val candidates = sequenceOf(sharedText, intent?.dataString.orEmpty())
        val urlPattern = Regex(
            "https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/(?:p|reel|reels|tv)/[A-Za-z0-9_-]+(?:[/?#][^\\s]*)?",
            RegexOption.IGNORE_CASE
        )
        return candidates
            .mapNotNull { urlPattern.find(it)?.value }
            .firstOrNull()
    }
}
