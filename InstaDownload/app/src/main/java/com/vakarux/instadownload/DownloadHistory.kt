package com.vakarux.instadownload

import android.content.Context
import com.vakarux.instadownload.data.DownloadHistory as RealDownloadHistory
import com.vakarux.instadownload.data.DownloadedFiles as RealDownloadedFiles
import com.vakarux.instadownload.data.isMediaUriAccessible as realIsMediaUriAccessible

typealias DownloadHistory = RealDownloadHistory
typealias DownloadedFiles = RealDownloadedFiles

fun isMediaUriAccessible(context: Context, uriString: String): Boolean =
    realIsMediaUriAccessible(context, uriString)
