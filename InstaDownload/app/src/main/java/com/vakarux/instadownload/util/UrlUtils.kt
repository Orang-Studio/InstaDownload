package com.vakarux.instadownload.util

import java.util.regex.Pattern

object UrlUtils {
    private val INSTAGRAM_URL_PATTERN = Pattern.compile(
        "^https?://(www\\.)?instagram\\.com/(p|reel|reels|stories|tv|share|[^/]+)/.*",
        Pattern.CASE_INSENSITIVE
    )

    fun isValidInstagramUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        return INSTAGRAM_URL_PATTERN.matcher(trimmed).matches()
    }

    fun isStoryUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.contains("/stories/", ignoreCase = true)
    }

    fun isProfileUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!isValidInstagramUrl(trimmed)) return false
        if (trimmed.contains("/p/") || trimmed.contains("/reel/") ||
            trimmed.contains("/reels/") || trimmed.contains("/tv/") ||
            trimmed.contains("/stories/")) {
            return false
        }
        val path = trimmed.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").removePrefix("instagram.com/")
            .substringBefore('?').substringBefore('/')
        return path.isNotBlank() && path !in listOf("explore", "accounts", "direct", "stories", "reels")
    }
}

