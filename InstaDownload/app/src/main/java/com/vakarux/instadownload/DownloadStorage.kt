package com.vakarux.instadownload

import android.content.Context
import com.vakarux.instadownload.model.MediaResult
import com.vakarux.instadownload.data.SaveOutcome as RealSaveOutcome
import com.vakarux.instadownload.data.saveToDownloads as realSaveToDownloads

typealias SaveOutcome = RealSaveOutcome

fun saveToDownloads(
    item: MediaResult,
    context: Context,
    settings: AppSettings,
    treeUri: String? = settings.downloadTreeUri
): SaveOutcome = realSaveToDownloads(item, context, settings, treeUri)
