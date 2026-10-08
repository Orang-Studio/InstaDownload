package com.vakarux.instadownload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore

internal fun saveToDownloads(
    item: MediaResult,
    context: Context,
    downloadTreeUri: String?
) {
    val mediaUrl = item.url
    val isVideo = item.isVideo
    val base = item.baseName.ifBlank { "instagram_${System.currentTimeMillis()}" }
    val fileName = if (isVideo) "$base.mp4" else "$base.jpg"
    val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

    if (downloadTreeUri != null) {
        val treeUri = Uri.parse(downloadTreeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val fileUri = DocumentsContract.createDocument(
            context.contentResolver, parent, mimeType, fileName
        ) ?: throw Exception(context.getString(R.string.error_create_file_in_folder))
        context.contentResolver.openOutputStream(fileUri)?.use { out ->
            InstagramDownloader.downloadToStream(mediaUrl, out)
        } ?: throw Exception(context.getString(R.string.error_write_to_folder))
        if (!isVideo) tagJpeg(item.meta) {
            context.contentResolver.openFileDescriptor(fileUri, "rw")?.use(it)
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/InstaDownload")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            item.meta?.let {
                put(MediaStore.MediaColumns.TITLE, it.caption?.take(100) ?: base)
                put(MediaStore.MediaColumns.ARTIST, it.username)
            }
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw Exception(context.getString(R.string.error_create_file_in_downloads))
        resolver.openOutputStream(uri)?.use { out ->
            InstagramDownloader.downloadToStream(mediaUrl, out)
        }
        if (!isVideo) tagJpeg(item.meta) { resolver.openFileDescriptor(uri, "rw")?.use(it) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } else {
        try {
            val dir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaDownload"
            ).apply { mkdirs() }
            val file = java.io.File(dir, fileName)
            file.outputStream().use { InstagramDownloader.downloadToStream(mediaUrl, it) }
            if (!isVideo) tagJpeg(item.meta) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use(it)
            }
        } catch (e: SecurityException) {
            throw Exception(context.getString(R.string.error_storage_permission_lost), e)
        }
    }
}

private fun tagJpeg(meta: PostMeta?, withFd: ((ParcelFileDescriptor) -> Unit) -> Unit) {
    if (meta == null) return
    runCatching {
        withFd { pfd ->
            val exif = android.media.ExifInterface(pfd.fileDescriptor)
            meta.username?.let { exif.setAttribute(android.media.ExifInterface.TAG_ARTIST, it) }
            meta.caption?.let { exif.setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, it) }
            exif.setAttribute(
                android.media.ExifInterface.TAG_USER_COMMENT,
                listOfNotNull(meta.url, meta.song?.let { "Song: $it" }).joinToString("\n")
            )
            if (meta.takenAtSec > 0) exif.setAttribute(
                android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(meta.takenAtSec * 1000))
            )
            exif.saveAttributes()
        }
    }
}
