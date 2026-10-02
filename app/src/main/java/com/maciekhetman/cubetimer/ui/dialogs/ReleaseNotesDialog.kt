package com.maciekhetman.cubetimer.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R

/** One released version and the strings listing what changed in it. */
private data class ReleaseNote(val version: String, val changes: List<Int>)

/**
 * Newest first. Add an entry (and its strings in `strings_release_notes.xml`, English and Polish)
 * whenever `versionName` in `app/build.gradle.kts` is bumped.
 */
private val ReleaseNotes = listOf(
    ReleaseNote(
        version = "2.0.0",
        changes = listOf(
            R.string.release_notes_2_0_0_sync,
            R.string.release_notes_2_0_0_account,
            R.string.release_notes_2_0_0_inspection,
            R.string.release_notes_2_0_0_start_delay,
            R.string.release_notes_2_0_0_settings,
            R.string.release_notes_2_0_0_polish,
            R.string.release_notes_2_0_0_csv,
        )
    ),
)

/** What changed in each released version of the app, newest first. */
@Composable
fun ReleaseNotesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.release_notes_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                ReleaseNotes.forEachIndexed { index, note ->
                    if (index > 0) Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.release_notes_version, note.version),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    note.changes.forEach { change ->
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            Text(text = "•", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(change),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
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
