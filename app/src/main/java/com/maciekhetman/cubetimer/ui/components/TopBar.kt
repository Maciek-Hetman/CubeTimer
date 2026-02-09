package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.Mode

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
    TopAppBar(
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
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary
    )
    val menuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onTertiaryContainer,
        leadingIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
        trailingIconColor = MaterialTheme.colorScheme.onTertiaryContainer
    )

    Box {
        Button(
            onClick = { expanded = true },
            colors = buttonColors,
            shape = MaterialTheme.shapes.extraLarge
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
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Mode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
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
