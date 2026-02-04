package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenu
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Spacer(modifier = Modifier.height(TopBarExtraTopPadding))
        TopAppBar(
            title = {
                TopBarTitleRow(
                    title = title,
                    currentMode = currentMode,
                    onModeSelected = onModeSelected,
                    collapsedFraction = 0f
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface
            ),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
    }
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Spacer(modifier = Modifier.height(TopBarExtraTopPadding))
        TopAppBar(
            title = {
                TopBarTitleRow(
                    title = title,
                    currentMode = currentMode,
                    onModeSelected = onModeSelected,
                    collapsedFraction = scrollBehavior.state.collapsedFraction
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface
            ),
            scrollBehavior = scrollBehavior,
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
    }
}

@Composable
private fun TopBarTitleRow(
    title: String,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    collapsedFraction: Float,
) {
    var expanded by remember { mutableStateOf(false) }
    val titleSize = lerp(34.sp, 24.sp, collapsedFraction)
    val modeSize = lerp(22.sp, 18.sp, collapsedFraction)
    val buttonHorizontalPadding = lerp(12.dp, 10.dp, collapsedFraction)
    val buttonVerticalPadding = lerp(8.dp, 6.dp, collapsedFraction)
    val iconSize = lerp(24.dp, 20.dp, collapsedFraction)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = titleSize,
            fontWeight = FontWeight.Bold
        )

        Box {
            FilledTonalButton(
                onClick = { expanded = true },
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(
                    horizontal = buttonHorizontalPadding,
                    vertical = buttonVerticalPadding
                )
            ) {
                Text(
                    text = currentMode.displayName,
                    fontSize = modeSize,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select mode",
                    modifier = Modifier.size(iconSize)
                )
            }

            ExpressiveDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Mode.entries.forEach { mode ->
                    ExpressiveDropdownMenuItem(
                        text = {
                            Text(
                                text = mode.displayName,
                                fontWeight = if (currentMode == mode) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onModeSelected(mode)
                            expanded = false
                        },
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
}

private val TopBarExtraTopPadding = 16.dp
