package com.vakarux.instadownload.model

data class PostMeta(
    val username: String?,
    val caption: String?,
    val url: String,
    val song: String?,
    val takenAtSec: Long,
)

data class MediaResult(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val reduced: Boolean = false,
    val baseName: String = "",
    val meta: PostMeta? = null,
) {
    val previewUrl: String? get() = thumbnailUrl ?: url.takeIf { !isVideo }
}

enum class PostType { PHOTO, VIDEO, CAROUSEL }

data class ProfilePost(
    val code: String,
    val type: PostType,
    val takenAtSec: Long,
    val caption: String,
    val previewUrl: String?,
    val mediaCount: Int,
)

class ProfilePage(
    val posts: List<ProfilePost>,
    val nextCursor: String?,
    val profilePicUrl: String?
)

