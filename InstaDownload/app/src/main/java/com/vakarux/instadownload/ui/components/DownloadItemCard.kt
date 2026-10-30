package com.vakarux.instadownload.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vakarux.instadownload.AppSettings
import com.vakarux.instadownload.DownloadedFiles
import com.vakarux.instadownload.R
import com.vakarux.instadownload.model.DownloadKind
import com.vakarux.instadownload.model.DownloadRecord
import com.vakarux.instadownload.model.kind
import com.vakarux.instadownload.ui.AppIcons
import com.vakarux.instadownload.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemCard(
    record: DownloadRecord,
    onPreview: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val isDark = isAppInDarkTheme()

    val exists by produceState<Boolean?>(null, record.uri) {
        value = withContext(Dispatchers.IO) { DownloadedFiles.exists(context, record) }
    }
    val size by produceState(-1L, record.uri) {
        value = withContext(Dispatchers.IO) { DownloadedFiles.sizeBytes(context, record) }
    }

    var captionExpanded by remember(record.uri) { mutableStateOf(false) }
    var captionHasOverflow by remember(record.uri) { mutableStateOf(false) }
    val captionScrollState = rememberScrollState()

    val meta = record.meta
    val username = meta?.username?.takeIf { it.isNotBlank() }
    val caption = meta?.caption?.takeIf { it.isNotBlank() }

    // Pure crisp white in light mode, sleek dark container in dark mode
    val cardBg = if (isDark) Color(0xFF1E1E24) else Color.White
    val cardBorder = if (isDark) Color(0xFF2C2C30) else Color(0xFFEBEBF0)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        ),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 0.dp else 1.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = Color(0x0A000000),
                spotColor = Color(0x10000000)
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // ── Thumbnail ───────────────────────────────────────────────
                CompactThumbnail(
                    record = record,
                    exists = exists,
                    onClick = onPreview
                )

                Spacer(modifier = Modifier.width(10.dp))

                // ── Content Info ────────────────────────────────────────────
                Column(modifier = Modifier.weight(1f)) {
                    // Header: Username + Post Type Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = username?.let { "@$it" } ?: stringResource(R.string.unknown_account),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(
                                    if (username == null) Modifier else Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = {
                                            copyTextToClipboard(
                                                context = context,
                                                label = context.getString(R.string.handle_clipboard_label),
                                                text = "@$username",
                                                confirmation = R.string.handle_copied
                                            )
                                        }
                                    )
                                )
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Type Pill Badge
                        val badgeText = when (record.kind) {
                            DownloadKind.REELS -> "Reel"
                            DownloadKind.STORIES -> "Story"
                            DownloadKind.POSTS -> "Post"
                            DownloadKind.ALL -> if (record.mimeType.startsWith("video/")) "Video" else "Photo"
                        }
                        val badgeColor = when (record.kind) {
                            DownloadKind.REELS -> Color(0xFFE1306C)
                            DownloadKind.STORIES -> Color(0xFFF77737)
                            else -> MaterialTheme.colorScheme.primary
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.12f),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Caption
                    if (caption != null) {
                        Text(
                            text = caption,
                            maxLines = if (captionExpanded) Int.MAX_VALUE else 2,
                            overflow = if (captionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onTextLayout = { captionHasOverflow = it.hasVisualOverflow },
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .then(
                                    if (captionExpanded) {
                                        Modifier.heightIn(max = 140.dp).verticalScroll(captionScrollState)
                                    } else Modifier
                                )
                                .combinedClickable(
                                    onClick = { captionExpanded = !captionExpanded },
                                    onLongClick = {
                                        copyTextToClipboard(
                                            context = context,
                                            label = context.getString(R.string.caption_clipboard_label),
                                            text = caption,
                                            confirmation = R.string.caption_copied
                                        )
                                    }
                                )
                        )

                        if (captionHasOverflow || captionExpanded) {
                            Text(
                                text = stringResource(
                                    if (captionExpanded) R.string.show_less_label else R.string.show_more_label
                                ),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { captionExpanded = !captionExpanded }
                            )
                        }
                    }

                    // Metadata Row: Dimensions • File Size • Download Date
                    val metadataParts = listOfNotNull(
                        record.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" },
                        size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                        formatTimestamp(record.downloadedAt)
                    )
                    Text(
                        text = metadataParts.joinToString(" • "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    // Post Source Link
                    meta?.url?.let { postUrl ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .clickable { uriHandler.openUri(postUrl) }
                        ) {
                            Icon(
                                imageVector = AppIcons.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Instagram Post",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Missing file indicator if deleted from storage
            if (exists == false) {
                Text(
                    text = stringResource(R.string.file_missing),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // Subtle divider before option buttons
            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                thickness = 0.6.dp,
                color = if (isDark) Color(0xFF2C2C30) else Color(0xFFEEEEF2)
            )

            // ── Option Buttons Row ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Preview
                CompactOptionPill(
                    text = stringResource(R.string.preview_label),
                    icon = AppIcons.Visibility,
                    enabled = exists != false,
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onPreview
                )

                // 2. Open
                CompactOptionPill(
                    text = stringResource(R.string.open_label),
                    icon = AppIcons.OpenInNew,
                    enabled = exists != false,
                    containerColor = if (isDark) Color(0xFF26262B) else Color(0xFFF3F3F6),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = { launchFileIntent(context) { DownloadedFiles.openIntent(context, record) } }
                )

                // 3. Share
                CompactOptionPill(
                    text = stringResource(R.string.share_label),
                    icon = AppIcons.Share,
                    enabled = exists != false,
                    containerColor = if (isDark) Color(0xFF26262B) else Color(0xFFF3F3F6),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    onClick = { launchFileIntent(context) { DownloadedFiles.shareIntent(context, record) } }
                )

                // 4. Delete
                CompactOptionPill(
                    text = stringResource(R.string.delete_label),
                    icon = AppIcons.Delete,
                    enabled = true,
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.09f),
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (DownloadedFiles.delete(context, record)) {
                            onDeleted()
                        } else {
                            Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactOptionPill(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = text,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactThumbnail(
    record: DownloadRecord,
    exists: Boolean?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, record.uri, exists) {
        if (exists != false) {
            value = withContext(Dispatchers.IO) {
                DownloadedFiles.previewBitmap(context, record, 320)?.asImageBitmap()
            }
        }
    }

    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 86.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.07f))
            .clickable(enabled = exists != false, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(
                    if (record.mimeType.startsWith("video/")) R.string.video_preview_description
                    else R.string.image_preview_description
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: if (exists == null) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (record.mimeType.startsWith("video/")) AppIcons.Movie else AppIcons.Image,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(26.dp)
            )
        }

        // Video Play Icon Badge
        if (record.mimeType.startsWith("video/")) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.50f),
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Duration Pill (e.g. 00:27)
        if (record.durationSec > 0) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
            ) {
                Text(
                    text = DateUtils.formatElapsedTime(record.durationSec.roundToLong()),
                    color = Color.White,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }
    }
}

private fun copyTextToClipboard(
    context: Context,
    label: String,
    text: String,
    @androidx.annotation.StringRes confirmation: Int
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (AppSettings(context).hapticsEnabled) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, 120))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(35)
            }
        }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
}

private fun launchFileIntent(context: Context, intent: () -> android.content.Intent) {
    try {
        context.startActivity(intent())
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, R.string.file_action_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun formatTimestamp(timeMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timeMs))
