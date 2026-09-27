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
import androidx.compose.ui.text.style.TextOverflow

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
                text = "$selectedCount selected",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onSelectAllToggle) {
                Icon(
                    imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = if (isAllSelected) "Deselect All" else "Select All"
                )
            }
            IconButton(onClick = onExportSelected) {
                Icon(Icons.Outlined.FileDownload, contentDescription = "Export selected solves")
            }
            IconButton(onClick = onDeleteSelected) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete selected solves")
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
