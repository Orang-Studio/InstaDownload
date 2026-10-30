package com.vakarux.instadownload.model

import androidx.annotation.StringRes
import com.vakarux.instadownload.R

enum class DatePreset(@StringRes val labelRes: Int) {
    ANY(R.string.filter_date_any),
    WEEK(R.string.filter_date_week),
    MONTH(R.string.filter_date_month),
    YEAR(R.string.filter_date_year),
    CUSTOM(R.string.filter_date_custom),
}

data class PostFilter(
    val types: Set<PostType> = PostType.entries.toSet(),
    val datePreset: DatePreset = DatePreset.ANY,
    val fromSec: Long? = null,
    val toSec: Long? = null,
    val caption: String = "",
) {
    fun apply(posts: List<ProfilePost>): List<ProfilePost> {
        val query = caption.trim()
        return posts.filter { post ->
            post.type in types &&
                (fromSec == null || post.takenAtSec >= fromSec) &&
                (toSec == null || post.takenAtSec < toSec) &&
                (query.isEmpty() || post.caption.contains(query, ignoreCase = true))
        }
    }
}

