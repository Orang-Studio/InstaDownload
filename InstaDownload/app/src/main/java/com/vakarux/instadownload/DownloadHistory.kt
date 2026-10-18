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
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class DownloadRecord(
    val uri: String,
    val fileName: String,
    val mimeType: String,
    val downloadedAt: Long,
    val meta: PostMeta?,
    val width: Int,
    val height: Int,
    val durationSec: Double,
)

object DownloadHistory {
    private const val PREFS = "download_history"
    private const val KEY = "records"

    fun load(context: Context): List<DownloadRecord> = runCatching {
        val json = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]"))
        (0 until json.length()).mapNotNull { i -> json.optJSONObject(i)?.toRecord() }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, record: DownloadRecord) {
        save(context, listOf(record) + load(context).filterNot { it.uri == record.uri })
    }

    @Synchronized
    fun remove(context: Context, record: DownloadRecord) {
        save(context, load(context).filterNot { it.uri == record.uri })
    }

    private fun save(context: Context, records: List<DownloadRecord>) {
        val json = JSONArray().apply { records.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.toString()).apply()
    }

    private fun DownloadRecord.toJson() = JSONObject().apply {
        put("uri", uri)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("downloadedAt", downloadedAt)
        put("width", width)
        put("height", height)
        put("durationSec", durationSec)
        meta?.let {
            put("username", it.username ?: JSONObject.NULL)
            put("caption", it.caption ?: JSONObject.NULL)
            put("postUrl", it.url)
            put("song", it.song ?: JSONObject.NULL)
            put("takenAtSec", it.takenAtSec)
        }
    }

    private fun JSONObject.toRecord() = DownloadRecord(
        uri = optString("uri"),
        fileName = optString("fileName"),
        mimeType = optString("mimeType", "application/octet-stream"),
        downloadedAt = optLong("downloadedAt"),
        width = optInt("width"),
        height = optInt("height"),
        durationSec = optDouble("durationSec"),
        meta = optString("postUrl").takeIf { it.isNotBlank() }?.let {
            PostMeta(
                username = optString("username").takeUnless { it.isBlank() || it == "null" },
                caption = optString("caption").takeUnless { it.isBlank() || it == "null" },
                url = it,
                song = optString("song").takeUnless { it.isBlank() || it == "null" },
                takenAtSec = optLong("takenAtSec")
            )
        }
    ).takeIf { it.uri.isNotBlank() }
}

object DownloadedFiles {
    fun exists(context: Context, record: DownloadRecord): Boolean {
        val uri = Uri.parse(record.uri)
        if (uri.scheme == "file") return File(uri.path ?: return false).exists()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { it.moveToFirst() } == true
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

    fun previewBitmap(context: Context, record: DownloadRecord, maxSide: Int): Bitmap? = runCatching {
        if (record.mimeType.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, Uri.parse(record.uri))
                retriever.getFrameAtTime(0)?.let { scaleDown(it, maxSide) }
            } finally {
                retriever.release()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(context, record).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        openInput(context, record).use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    fun delete(context: Context, record: DownloadRecord): Boolean {
        val uri = Uri.parse(record.uri)
        return runCatching {
            if (uri.scheme == "file") {
                File(uri.path ?: return false).let { !it.exists() || it.delete() }
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri) || !exists(context, record)
            } else {
                context.contentResolver.delete(uri, null, null) > 0 || !exists(context, record)
            }
        }.getOrElse { !exists(context, record) }
    }

    fun openIntent(context: Context, record: DownloadRecord) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(shareUri(context, record), record.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareIntent(context: Context, record: DownloadRecord) = Intent(Intent.ACTION_SEND).apply {
        type = record.mimeType
        putExtra(Intent.EXTRA_STREAM, shareUri(context, record))
        clipData = ClipData.newUri(context.contentResolver, record.fileName, shareUri(context, record))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun shareUri(context: Context, record: DownloadRecord): Uri {
        val uri = Uri.parse(record.uri)
        return if (uri.scheme == "file") {
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(requireNotNull(uri.path))
            )
        } else uri
    }

    private fun openInput(context: Context, record: DownloadRecord): InputStream {
        val uri = Uri.parse(record.uri)
        return if (uri.scheme == "file") {
            FileInputStream(File(requireNotNull(uri.path)))
        } else {
            requireNotNull(context.contentResolver.openInputStream(uri))
        }
    }
}
