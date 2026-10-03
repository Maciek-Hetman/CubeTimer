package com.maciekhetman.cubetimer.ui.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.ui.components.formatDateTime
import com.maciekhetman.cubetimer.viewmodel.ConflictUiModel
import java.time.Instant

/**
 * Cloud sync status, plus - for signed-in users - the list of unresolved sync [conflicts] with
 * keep-this-device's / keep-server's actions. [resolvingConflictIds] are conflicts with a resolution
 * in flight (their buttons are disabled) and [conflictErrorMessage] reports a failed resolution.
 */
@Composable
fun SyncStatusDialog(
    syncState: SyncUiState,
    onTriggerSync: () -> Unit,
    onDismiss: () -> Unit,
    onLoginClick: () -> Unit,
    modifier: Modifier = Modifier,
    conflicts: List<ConflictUiModel> = emptyList(),
    resolvingConflictIds: Set<String> = emptySet(),
    conflictErrorMessage: String? = null,
    onKeepLocal: (String) -> Unit = {},
    onKeepServer: (String) -> Unit = {}
) {
    // conflictCount comes from the database; the list is mapped asynchronously and may lag behind it.
    val hasConflicts = syncState.conflictCount > 0 || conflicts.isNotEmpty()
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_rotation_val"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (syncState.status) {
                    SyncStatusType.SYNCED -> {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = stringResource(R.string.sync_status_synced),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    SyncStatusType.SYNCING -> {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = stringResource(R.string.sync_status_syncing),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(28.dp)
                                .rotate(rotation)
                        )
                    }
                    SyncStatusType.OFFLINE -> {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = stringResource(R.string.sync_status_offline),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    SyncStatusType.ERROR -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = stringResource(R.string.sync_status_error),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.sync_dialog_title))
            }
        },
        text = {
            // Scrollable: a long conflict list must not push the buttons off small screens.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (syncState.isGuest) {
                    Text(
                        text = stringResource(R.string.sync_dialog_guest),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.sync_dialog_guest_pitch),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else {
                    val statusText = stringResource(
                        when (syncState.status) {
                            SyncStatusType.SYNCED ->
                                if (hasConflicts) R.string.sync_dialog_needs_review else R.string.sync_dialog_up_to_date
                            SyncStatusType.SYNCING -> R.string.sync_dialog_syncing
                            SyncStatusType.OFFLINE -> R.string.sync_dialog_offline
                            SyncStatusType.ERROR -> R.string.sync_dialog_error
                        }
                    )

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (syncState.lastSyncTime != null) {
                        Text(
                            text = stringResource(R.string.sync_dialog_last_synced, formatSyncTime(syncState.lastSyncTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (syncState.pendingCount > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.sync_dialog_pending, syncState.pendingCount, syncState.pendingCount),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Not syncState.errorMessage: that is raw, untranslated exception/server text.
                    if (syncState.status == SyncStatusType.ERROR) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.sync_dialog_error_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (conflicts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        SyncConflictList(
                            conflicts = conflicts,
                            onKeepLocal = onKeepLocal,
                            onKeepServer = onKeepServer,
                            resolvingIds = resolvingConflictIds,
                            errorMessage = conflictErrorMessage
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (syncState.isGuest) {
                Button(
                    shape = RoundedCornerShape(20.dp),
                    onClick = {
                        onDismiss()
                        onLoginClick()
                    }
                ) {
                    Text(stringResource(R.string.auth_sign_in))
                }
            } else {
                Button(
                    shape = RoundedCornerShape(20.dp),
                    onClick = onTriggerSync,
                    enabled = syncState.status != SyncStatusType.SYNCING
                ) {
                    Text(stringResource(R.string.sync_dialog_sync_now))
                }
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(20.dp),
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.action_close))
            }
        },
        modifier = modifier
    )
}

/** The stored ISO-8601 sync time in the app's language and the device's time zone; unparseable values as stored. */
private fun formatSyncTime(iso: String): String {
    val millis = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return iso
    return formatDateTime(millis)
}
