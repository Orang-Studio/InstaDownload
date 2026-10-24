package com.vakarux.instadownload

import java.util.regex.Pattern

data class InstagramStoryLink(
    val username: String,
    val mediaId: String,
) {
    val url: String
        get() = "https://www.instagram.com/stories/$username/$mediaId/"
}

internal object InstagramStoryLinks {
    private val pattern = Pattern.compile(
        "^https?://(?:www\\.)?(?:instagram\\.com|instagr\\.am)/stories/([A-Za-z0-9._]+)/([0-9_]+)(?:/)?(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(value: String): InstagramStoryLink? {
        val match = pattern.matcher(value.trim())
        return if (match.matches()) {
            InstagramStoryLink(
                username = match.group(1)!!,
                mediaId = match.group(2)!!,
            )
        } else {
            null
        }
    }
}
