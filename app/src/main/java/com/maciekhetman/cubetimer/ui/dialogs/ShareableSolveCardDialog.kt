package com.maciekhetman.cubetimer.ui.dialogs

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.maciekhetman.cubetimer.domain.HistoricalPbCalculator
import com.maciekhetman.cubetimer.domain.TimeFormatter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.ui.components.Scramble2DPreview
import com.maciekhetman.cubetimer.viewmodel.SolveDetailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * State holder for capturing composable drawings into an [android.graphics.Picture].
 */
class PictureCaptureState {
    val picture = android.graphics.Picture()
    var widthPx: Int = 0
    var heightPx: Int = 0

    fun captureBitmap(): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Bitmap.createBitmap(picture)
            } else {
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawPicture(picture)
                bitmap
            }
        } catch (e: Exception) {
            Log.e("PictureCaptureState", "Failed to create bitmap from Picture", e)
            null
        }
    }
}

/**
 * Modifier that records the content drawing operations into [PictureCaptureState]
 * while allowing normal native rendering on screen.
 */
fun Modifier.recordPicture(captureState: PictureCaptureState): Modifier = this.drawWithCache {
    val width = size.width.toInt()
    val height = size.height.toInt()
    captureState.widthPx = width
    captureState.heightPx = height

    onDrawWithContent {
        drawContent()
        if (width > 0 && height > 0) {
            val pictureCanvas = androidx.compose.ui.graphics.Canvas(
                captureState.picture.beginRecording(width, height)
            )
            draw(this, this.layoutDirection, pictureCanvas, this.size) {
                this@onDrawWithContent.drawContent()
            }
            captureState.picture.endRecording()
        }
    }
}

/**
 * Helper object providing share text formatting and file sharing utilities.
 */
object SolveShareHelper {

    private val shareDateFormat by lazy {
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.ENGLISH)
    }

    fun formatSolveDate(timestamp: Long): String {
        return shareDateFormat.format(Date(timestamp))
    }

    fun formatShareText(
        solve: SolveTime,
        isPb: Boolean = false,
        pbDeltaText: String? = null
    ): String {
        val timeString = when (solve.penalty) {
            Penalty.DNF -> "DNF"
            Penalty.PLUS_TWO -> "${TimeFormatter.formatTime(solve.displayTime)}s (+2)"
            Penalty.NONE -> "${TimeFormatter.formatTime(solve.displayTime)}s"
        }
        val pbSuffix = if (isPb && !pbDeltaText.isNullOrBlank()) " [$pbDeltaText]" else ""
        val formattedDate = formatSolveDate(solve.timestamp)

        return buildString {
            appendLine("CubeTimer - ${solve.mode.displayName} Solve")
            appendLine("Time: $timeString$pbSuffix")
            if (solve.scramble.isNotBlank()) {
                appendLine("Scramble: ${solve.scramble}")
            }
            appendLine("Date: $formattedDate")
            append("Timer: Screen / Touch")
        }
    }

    fun saveBitmapToShareCache(context: Context, bitmap: Bitmap, solveId: String): File {
        val shareDir = File(context.cacheDir, "share_images")
        if (!shareDir.exists()) {
            shareDir.mkdirs()
        }

        // Prune old images to avoid cache bloat
        try {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            shareDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}

        val cleanId = solveId.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "solve" }
        val file = File(shareDir, "solve_${cleanId}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        return file
    }

    fun launchShareIntent(context: Context, imageUri: Uri, shareText: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_TITLE, "CubeTimer Solve")
            clipData = ClipData.newRawUri("CubeTimer Solve Card", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(sendIntent, "Share Solve").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    fun launchTextShareIntent(context: Context, shareText: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_TITLE, "CubeTimer Solve")
        }
        val chooser = Intent.createChooser(sendIntent, "Share Solve").apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }
}

/**
 * Interactive Shareable Solve Card Pop-up Modal Dialog.
 */
@Composable
fun ShareableSolveCardDialog(
    solve: SolveTime,
    solveNumber: Int,
    priorBestTime: Long?,
    isPb: Boolean,
    pbDelta: Long?,
    formattedPbDelta: String? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val pictureCaptureState = remember { PictureCaptureState() }

    var isSharing by remember { mutableStateOf(false) }

    val eventTitle = when (solve.mode) {
        Mode.CUBE_2x2 -> "2x2 Cube"
        Mode.CUBE_3x3 -> "3x3 Cube"
        Mode.CUBE_4x4 -> "4x4 Cube"
        Mode.CUBE_5x5 -> "5x5 Cube"
        Mode.MEGAMINX -> "Megaminx"
        Mode.PYRAMINX -> "Pyraminx"
    }

    val pbDisplayText = formattedPbDelta ?: run {
        if (isPb) {
            if (priorBestTime != null && pbDelta != null) {
                HistoricalPbCalculator.formatPbDelta(pbDelta, priorBestTime)
            } else {
                "PB (First solve)"
            }
        } else {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // =========================================================================
                // 1. DEDICATED SHAREABLE SOLVE CARD (CAPTURED TO BITMAP)
                // =========================================================================
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .recordPicture(pictureCaptureState),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Card Header: Event title and Solve number
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Text(
                                    text = eventTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }

                            Text(
                                text = "Solve #$solveNumber",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Formatted Date
                        Text(
                            text = SolveShareHelper.formatSolveDate(solve.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Solve Duration & Penalty Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = TimeFormatter.formatTime(solve.displayTime),
                                style = MaterialTheme.typography.displayMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = when (solve.penalty) {
                                    Penalty.DNF -> MaterialTheme.colorScheme.error
                                    Penalty.PLUS_TWO -> MaterialTheme.colorScheme.tertiary
                                    Penalty.NONE -> MaterialTheme.colorScheme.onSurface
                                }
                            )

                            if (solve.penalty != Penalty.NONE) {
                                Surface(
                                    color = when (solve.penalty) {
                                        Penalty.DNF -> MaterialTheme.colorScheme.errorContainer
                                        Penalty.PLUS_TWO -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> Color.Transparent
                                    },
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        text = when (solve.penalty) {
                                            Penalty.DNF -> "DNF"
                                            Penalty.PLUS_TWO -> "+2"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        color = when (solve.penalty) {
                                            Penalty.DNF -> MaterialTheme.colorScheme.onErrorContainer
                                            Penalty.PLUS_TWO -> MaterialTheme.colorScheme.onTertiaryContainer
                                            else -> Color.Unspecified
                                        }
                                    )
                                }
                            }
                        }

                        // Historical PB Indicator Chip
                        if (isPb && pbDisplayText != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.EmojiEvents,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = pbDisplayText,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        // Timing Device Metadata Chip
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TouchApp,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Timer: Screen / Touch",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 2D Scramble Preview Net
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Scramble2DPreview(
                                scramble = solve.scramble,
                                mode = solve.mode,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Full Scramble Text with Copy Button
                        if (solve.scramble.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = solve.scramble,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        lineHeight = 16.sp
                                    )
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            clipboardManager.setText(AnnotatedString(solve.scramble))
                                            Toast.makeText(context, "Scramble copied!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy scramble",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Branding Footer
                        Text(
                            text = "CubeTimer",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            letterSpacing = 1.sp
                        )
                    }
                }

                // =========================================================================
                // 2. MODAL CONTROLS (OUTSIDE CAPTURED BITMAP)
                // =========================================================================
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Close")
                    }

                    Button(
                        onClick = {
                            if (isSharing) return@Button
                            isSharing = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                            coroutineScope.launch {
                                try {
                                    val bitmap = pictureCaptureState.captureBitmap()
                                    val shareText = SolveShareHelper.formatShareText(
                                        solve = solve,
                                        isPb = isPb,
                                        pbDeltaText = pbDisplayText
                                    )

                                    if (bitmap != null) {
                                        val file = withContext(Dispatchers.IO) {
                                            SolveShareHelper.saveBitmapToShareCache(context, bitmap, solve.id)
                                        }
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        SolveShareHelper.launchShareIntent(context, uri, shareText)
                                    } else {
                                        // Fallback to text-only share
                                        SolveShareHelper.launchTextShareIntent(context, shareText)
                                    }
                                } catch (e: Exception) {
                                    Log.e("ShareableSolveCard", "Sharing failed", e)
                                    Toast.makeText(context, "Could not share solve: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isSharing = false
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = !isSharing
                    ) {
                        if (isSharing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Overload accepting [SolveDetailState].
 */
@Composable
fun ShareableSolveCardDialog(
    detail: SolveDetailState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ShareableSolveCardDialog(
        solve = detail.solve,
        solveNumber = detail.solveNumber,
        priorBestTime = detail.priorBestTime,
        isPb = detail.isPb,
        pbDelta = detail.pbDelta,
        formattedPbDelta = detail.formattedPbDelta,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
