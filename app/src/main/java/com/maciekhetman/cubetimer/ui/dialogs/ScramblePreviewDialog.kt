package com.maciekhetman.cubetimer.ui.dialogs

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.ui.components.Scramble2DPreview
import kotlinx.coroutines.launch

/**
 * Modal dialog displaying the 2D visual puzzle net and full scramble text
 * when tapping the scramble card on the Timer screen.
 */
@Composable
fun ScramblePreviewDialog(
    scramble: String,
    mode: Mode,
    onDismiss: () -> Unit,
    onGenerateNewScramble: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val eventTitle = when (mode) {
        Mode.CUBE_2x2, Mode.CUBE_3x3, Mode.CUBE_4x4, Mode.CUBE_5x5 ->
            stringResource(R.string.scramble_preview_cube_title, mode.displayName)
        Mode.MEGAMINX, Mode.PYRAMINX -> mode.displayName
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header: Event Title and "Scramble Preview" label
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
                        text = stringResource(R.string.scramble_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2D Scramble Preview Net
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Scramble2DPreview(
                        scramble = scramble,
                        mode = mode,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Full Scramble Text with Copy Button
                if (scramble.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = scramble,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                lineHeight = 18.sp
                            )
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    coroutineScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText(resources.getString(R.string.scramble_clip_label), scramble))
                                        )
                                        Toast.makeText(context, R.string.scramble_copied, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.scramble_copy),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons: Close & New Scramble
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_close),
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onGenerateNewScramble()
                        },
                        modifier = Modifier
                            .weight(1.4f)
                            .height(48.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.scramble_new),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
