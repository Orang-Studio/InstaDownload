package com.vakarux.instadownload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore

internal class SaveOutcome(val record: DownloadRecord, val fellBackToDefaultFolder: Boolean)

/** Saves [item] and adds it to [DownloadHistory]. */
internal fun saveToDownloads(item: MediaResult, context: Context, settings: AppSettings): SaveOutcome {
    val base = item.baseName.ifBlank { "instagram_${System.currentTimeMillis()}" }
    val fileName = if (item.isVideo) "$base.mp4" else "$base.jpg"
    val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
    val (savedUri, fellBack) = try {
        saveTo(item, context, settings.downloadTreeUri, fileName, mimeType) to false
    } catch (e: Exception) {
        if (settings.downloadTreeUri == null) throw e
        settings.downloadTreeUri = null
        settings.downloadFolderName = AppSettings.DEFAULT_FOLDER_NAME
        saveTo(item, context, null, fileName, mimeType) to true
    }
    val record = DownloadRecord(
        uri = savedUri.toString(),
        fileName = fileName,
        mimeType = mimeType,
        downloadedAtMs = System.currentTimeMillis(),
        postUrl = item.meta?.url,
        username = item.meta?.username,
        caption = item.meta?.caption,
        song = item.meta?.song,
        takenAtSec = item.meta?.takenAtSec ?: 0L,
        width = item.width.takeIf { it < Int.MAX_VALUE } ?: 0,
        height = item.height,
        durationSec = item.durationSec,
    )
    DownloadHistory.add(context, record)
    return SaveOutcome(record, fellBack)
}

/** Returns the Uri of the saved file: a document, MediaStore or file:// Uri depending on the branch. */
private fun saveTo(
    item: MediaResult,
    context: Context,
    downloadTreeUri: String?,
    fileName: String,
    mimeType: String
): Uri {
    val mediaUrl = item.url
    val isVideo = item.isVideo
    val base = fileName.substringBeforeLast('.')

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
        return fileUri
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
        return uri
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
            return Uri.fromFile(file)
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
