package com.maciekhetman.cubetimer.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.maciekhetman.cubetimer.R

/**
 * Contextual top app bar shown on the History screen while solves are multi-selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryContextualTopAppBar(
    selectedCount: Int,
    isAllSelected: Boolean,
    onDismiss: () -> Unit,
    onSelectAllToggle: () -> Unit,
    onExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Text(
                text = pluralStringResource(R.plurals.selection_count, selectedCount, selectedCount),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_cancel))
            }
        },
        actions = {
            IconButton(onClick = onSelectAllToggle) {
                Icon(
                    imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = stringResource(if (isAllSelected) R.string.selection_deselect_all else R.string.selection_select_all)
                )
            }
            IconButton(onClick = onExportSelected) {
                Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.selection_export))
            }
            IconButton(onClick = onDeleteSelected) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.selection_delete))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        modifier = modifier
    )
}
