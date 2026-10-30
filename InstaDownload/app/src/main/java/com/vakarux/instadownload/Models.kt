package com.vakarux.instadownload

import com.vakarux.instadownload.model.kind as modelKind

// Re-export all models from the com.vakarux.instadownload.model package for seamless backwards compatibility
typealias PostMeta = com.vakarux.instadownload.model.PostMeta
typealias MediaResult = com.vakarux.instadownload.model.MediaResult
typealias PostType = com.vakarux.instadownload.model.PostType
typealias ProfilePost = com.vakarux.instadownload.model.ProfilePost
typealias ProfilePage = com.vakarux.instadownload.model.ProfilePage
typealias DownloadRecord = com.vakarux.instadownload.model.DownloadRecord
typealias DownloadKind = com.vakarux.instadownload.model.DownloadKind
typealias DownloadQuality = com.vakarux.instadownload.model.DownloadQuality
typealias AppTheme = com.vakarux.instadownload.model.AppTheme
typealias DatePreset = com.vakarux.instadownload.model.DatePreset
typealias PostFilter = com.vakarux.instadownload.model.PostFilter

val DownloadRecord.kind: DownloadKind
    get() = this.modelKind
