package com.maciekhetman.cubetimer.ui.sync

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.viewmodel.ConflictSideUi
import com.maciekhetman.cubetimer.viewmodel.ConflictUiModel

/** "1 conflict needs review" / "3 conflicts need review". */
fun syncConflictLabel(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.sync_conflicts_need_review, count, count)

/**
 * Lists unresolved sync conflicts, each comparing this device's version with the server's and
 * offering a button to keep either one.
 */
@Composable
fun SyncConflictList(
    conflicts: List<ConflictUiModel>,
    onKeepLocal: (String) -> Unit,
    onKeepServer: (String) -> Unit,
    modifier: Modifier = Modifier,
    resolvingIds: Set<String> = emptySet(),
    errorMessage: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sync_conflict_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = syncConflictLabel(LocalResources.current, conflicts.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = stringResource(R.string.sync_conflicts_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        conflicts.forEach { conflict ->
            SyncConflictCard(
                conflict = conflict,
                resolving = conflict.id in resolvingIds,
                onKeepLocal = { onKeepLocal(conflict.id) },
                onKeepServer = { onKeepServer(conflict.id) }
            )
        }
    }
}

@Composable
private fun SyncConflictCard(
    conflict: ConflictUiModel,
    resolving: Boolean,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_conflict_${conflict.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = conflict.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            ConflictSide(label = stringResource(R.string.sync_conflict_this_device), side = conflict.local)
            Spacer(modifier = Modifier.height(6.dp))
            ConflictSide(label = stringResource(R.string.sync_conflict_server), side = conflict.server)
            Spacer(modifier = Modifier.height(10.dp))
            FilledTonalButton(
                onClick = onKeepLocal,
                enabled = !resolving,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sync_conflict_keep_local))
            }
            OutlinedButton(
                onClick = onKeepServer,
                enabled = !resolving,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sync_conflict_keep_server))
            }
        }
    }
}

@Composable
private fun ConflictSide(label: String, side: ConflictSideUi) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        side.lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (side.deleted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
