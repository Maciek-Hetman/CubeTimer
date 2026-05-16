package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.Mode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = { Text(title) },
        actions = {
            ModeMenu(
                currentMode = currentMode,
                onModeSelected = onModeSelected
            )
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTopBar(
    title: String,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    LargeTopAppBar(
        title = { Text(title) },
        actions = {
            ModeMenu(
                currentMode = currentMode,
                onModeSelected = onModeSelected
            )
        },
        scrollBehavior = scrollBehavior,
        modifier = modifier
    )
}

@Composable
private fun ModeMenu(
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val menuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurface,
        trailingIconColor = MaterialTheme.colorScheme.primary
    )

    Box {
        FilledTonalButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                expanded = true
            },
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = 8.dp
            ),
            modifier = Modifier.padding(top = 12.dp, end = 8.dp)
        ) {
            Text(currentMode.displayName)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select mode"
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Mode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onModeSelected(mode)
                        expanded = false
                    },
                    colors = menuItemColors,
                    trailingIcon = if (currentMode == mode) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected mode"
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}
