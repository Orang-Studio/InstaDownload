package com.vakarux.instadownload.model

import com.vakarux.instadownload.R

data class DownloadRecord(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val downloadedAt: Long,
    val meta: PostMeta?,
    val width: Int,
    val height: Int,
    val durationSec: Double,
)

enum class DownloadKind(val labelRes: Int) {
    ALL(R.string.downloads_all),
    REELS(R.string.downloads_reels),
    POSTS(R.string.downloads_posts),
    STORIES(R.string.downloads_stories)
}

val DownloadRecord.kind: DownloadKind
    get() = when {
        meta?.url?.contains("/stories/") == true -> DownloadKind.STORIES
        meta?.url?.contains("/reel/") == true || meta?.url?.contains("/reels/") == true -> DownloadKind.REELS
        else -> DownloadKind.POSTS
    }

data class BatchState(
    val total: Int = 0,
    val saved: Int = 0,
    val failed: Int = 0,
    val fellBack: Boolean = false,
    val running: Boolean = true
) {
    val done: Int get() = saved + failed
}

