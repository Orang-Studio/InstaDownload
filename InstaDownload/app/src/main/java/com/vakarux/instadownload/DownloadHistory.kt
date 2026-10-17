package com.vakarux.instadownload

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One file saved by the app, plus the post metadata known when it was downloaded. */
data class DownloadRecord(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val downloadedAtMs: Long,
    val postUrl: String?,
    val username: String?,
    val caption: String?,
    val song: String?,
    val takenAtSec: Long,
    val width: Int,
    val height: Int,
    val durationSec: Double,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri)
        .put("fileName", fileName)
        .put("mimeType", mimeType)
        .put("downloadedAtMs", downloadedAtMs)
        .putOpt("postUrl", postUrl)
        .putOpt("username", username)
        .putOpt("caption", caption)
        .putOpt("song", song)
        .put("takenAtSec", takenAtSec)
        .put("width", width)
        .put("height", height)
        .put("durationSec", durationSec)

    companion object {
        fun fromJson(json: JSONObject) = DownloadRecord(
            uri = json.getString("uri"),
            fileName = json.optString("fileName"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            downloadedAtMs = json.optLong("downloadedAtMs"),
            postUrl = json.optStringOrNull("postUrl"),
            username = json.optStringOrNull("username"),
            caption = json.optStringOrNull("caption"),
            song = json.optStringOrNull("song"),
            takenAtSec = json.optLong("takenAtSec"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            durationSec = json.optDouble("durationSec", 0.0),
        )

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}

/**
 * Keeps the list of downloaded files in a JSON file in app-private storage, newest first.
 * Both the main screen and the quick-download service write to it, so the list is
 * exposed as a flow the library screen can observe.
 */
object DownloadHistory {

    private const val FILE_NAME = "download_history.json"

    private val lock = Any()
    private val recordsFlow = MutableStateFlow<List<DownloadRecord>?>(null)

    fun records(context: Context): StateFlow<List<DownloadRecord>?> {
        if (recordsFlow.value == null) synchronized(lock) {
            if (recordsFlow.value == null) recordsFlow.value = readFromDisk(context)
        }
        return recordsFlow.asStateFlow()
    }

    fun add(context: Context, record: DownloadRecord) = update(context) { current ->
        listOf(record) + current.filterNot { it.uri == record.uri }
    }

    fun remove(context: Context, uris: Set<String>) = update(context) { current ->
        current.filterNot { it.uri in uris }
    }

    private fun update(context: Context, transform: (List<DownloadRecord>) -> List<DownloadRecord>) {
        synchronized(lock) {
            val current = recordsFlow.value ?: readFromDisk(context)
            val updated = transform(current)
            writeToDisk(context, updated)
            recordsFlow.value = updated
        }
    }

    private fun historyFile(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun readFromDisk(context: Context): List<DownloadRecord> = runCatching {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { runCatching { DownloadRecord.fromJson(it) }.getOrNull() }
        }
    }.getOrDefault(emptyList())

    private fun writeToDisk(context: Context, records: List<DownloadRecord>) {
        val file = historyFile(context)
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeText(JSONArray(records.map { it.toJson() }).toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }
}

/** File operations on downloaded media, whichever storage backend saved them. */
object DownloadedFiles {

    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A Uri other apps can read: file:// paths (Android 9 and older) go through the FileProvider. */
    fun shareableUri(context: Context, record: DownloadRecord): Uri {
        val uri = Uri.parse(record.uri)
        if (uri.scheme != "file") return uri
        return FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(requireNotNull(uri.path))
        )
    }

    fun exists(context: Context, record: DownloadRecord): Boolean {
        val uri = Uri.parse(record.uri)
        if (uri.scheme == "file") return File(uri.path ?: return false).exists()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    fun sizeBytes(context: Context, record: DownloadRecord): Long {
        val uri = Uri.parse(record.uri)
        if (uri.scheme == "file") return File(uri.path ?: return -1L).length()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    /** Deletes the file itself. Returns true when the file is gone afterwards. */
    fun delete(context: Context, record: DownloadRecord): Boolean {
        val uri = Uri.parse(record.uri)
        return runCatching {
            when {
                uri.scheme == "file" -> File(requireNotNull(uri.path)).let { !it.exists() || it.delete() }
                DocumentsContract.isDocumentUri(context, uri) ->
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                else -> {
                    context.contentResolver.delete(uri, null, null)
                    true
                }
            }
        }.getOrElse { !exists(context, record) }
    }

    fun openIntent(context: Context, record: DownloadRecord): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareableUri(context, record), record.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun shareIntent(context: Context, records: List<DownloadRecord>, title: String): Intent {
        val uris = records.map { shareableUri(context, it) }
        val mimeType = when {
            records.all { it.isVideo } -> "video/*"
            records.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mimeType
        send.clipData = ClipData.newUri(context.contentResolver, title, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title)
    }

    /** Small preview bitmap; call off the main thread. */
    fun thumbnail(context: Context, record: DownloadRecord, maxSidePx: Int): Bitmap? {
        thumbnailCache.get(record.uri)?.let { return it }
        val uri = Uri.parse(record.uri)
        val bitmap = runCatching {
            if (record.isVideo) videoFrame(context, uri, maxSidePx) else sampledImage(context, uri, maxSidePx)
        }.getOrNull() ?: return null
        thumbnailCache.put(record.uri, bitmap)
        return bitmap
    }

    private fun videoFrame(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0) ?: return null
            return scaleDown(frame, maxSidePx)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampledImage(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxSidePx) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun scaleDown(bitmap: Bitmap, maxSidePx: Int): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxSidePx) return bitmap
        val scale = maxSidePx.toFloat() / longestSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
