package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.TimerAverageOptions
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TimerViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsState()
    val defaultMode by viewModel.defaultMode.collectAsState()
    val amoledEnabled by viewModel.amoledEnabled.collectAsState()
    val showScrambleRefreshButton by viewModel.showScrambleRefreshButton.collectAsState()
    val scrambleScalePercent by viewModel.scrambleScalePercent.collectAsState()
    val timerStartDelayMillis by viewModel.timerStartDelayMillis.collectAsState()
    val timerAverages by viewModel.timerAverages.collectAsState()
    val runningTimerDisplay by viewModel.runningTimerDisplay.collectAsState()
    val hideScrambleDuringSolve by viewModel.hideScrambleDuringSolve.collectAsState()
    val hideAveragesDuringSolve by viewModel.hideAveragesDuringSolve.collectAsState()
    val hideLastResultsDuringSolve by viewModel.hideLastResultsDuringSolve.collectAsState()
    val hideLastResultsOnTimer by viewModel.hideLastResultsOnTimer.collectAsState()
    val focusMode by viewModel.focusMode.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    var defaultModeMenuExpanded by remember { mutableStateOf(value = false) }
    var scrambleScaleMenuExpanded by remember { mutableStateOf(value = false) }
    var runningTimerDisplayMenuExpanded by remember { mutableStateOf(value = false) }
    var timerAveragesExpanded by remember { mutableStateOf(value = false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CollapsingTopBar(
                title = "Settings",
                currentMode = currentMode,
                onModeSelected = onModeSelected,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        val startPadding = paddingValues.calculateStartPadding(layoutDirection)
        val endPadding = paddingValues.calculateEndPadding(layoutDirection)
        val bottomPadding = paddingValues.calculateBottomPadding()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = startPadding,
                top = paddingValues.calculateTopPadding() + 8.dp,
                end = endPadding,
                bottom = bottomPadding + 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "Appearance") {
                    SettingToggleRow(
                        title = "Dynamic color",
                        checked = dynamicColorEnabled,
                        onCheckedChange = { viewModel.setDynamicColorEnabled(it) }
                    )
                    if (!dynamicColorEnabled) {
                        SettingsDivider()
                        SettingToggleRow(
                            title = "AMOLED dark",
                            checked = amoledEnabled,
                            onCheckedChange = { viewModel.setAmoledEnabled(it) }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Scramble") {
                    SettingToggleRow(
                        title = "New scramble button",
                        checked = showScrambleRefreshButton,
                        onCheckedChange = { viewModel.setShowScrambleRefreshButton(it) }
                    )
                    SettingsDivider()
                    SettingMenuRow(
                        title = "Scramble size",
                        valueLabel = "$scrambleScalePercent%",
                        onClick = { scrambleScaleMenuExpanded = true },
                        menuExpanded = scrambleScaleMenuExpanded,
                        onDismissMenu = { scrambleScaleMenuExpanded = false }
                    ) {
                        ScrambleScaleOptions.forEach { percent ->
                            DropdownMenuItem(
                                text = { Text("$percent%") },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scrambleScaleMenuExpanded = false
                            viewModel.setScrambleScalePercent(percent)
                        },
                    )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Timer") {
                    SettingSliderRow(
                        title = "Start delay",
                        value = timerStartDelayMillis,
                        valueRange = 200f..1000f,
                        steps = 7,
                        onValueChangeFinished = { delayMillis ->
                            viewModel.setTimerStartDelayMillis(delayMillis)
                        }
                    )
                    SettingsDivider()
                    SettingMenuRow(
                        title = "During solve",
                        valueLabel = runningTimerDisplay.displayName,
                        onClick = { runningTimerDisplayMenuExpanded = true },
                        menuExpanded = runningTimerDisplayMenuExpanded,
                        onDismissMenu = { runningTimerDisplayMenuExpanded = false }
                    ) {
                        RunningTimerDisplay.entries.forEach { display ->
                            DropdownMenuItem(
                                text = { Text(display.displayName) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            runningTimerDisplayMenuExpanded = false
                            viewModel.setRunningTimerDisplay(display)
                        },
                    )
                        }
                    }
                    SettingsDivider()
                    SettingToggleRow(
                        title = "Focus mode",
                        checked = focusMode,
                        onCheckedChange = viewModel::setFocusMode
                    )
                    SettingsDivider()
                    SettingToggleRow(
                        title = "Hide scramble during solve",
                        checked = hideScrambleDuringSolve,
                        onCheckedChange = { viewModel.setHideScrambleDuringSolve(it) }
                    )
                    SettingsDivider()
                    SettingToggleRow(
                        title = "Hide averages during solve",
                        checked = hideAveragesDuringSolve,
                        onCheckedChange = { viewModel.setHideAveragesDuringSolve(it) }
                    )
                    SettingsDivider()
                    SettingToggleRow(
                        title = "Hide last results during solve",
                        checked = hideLastResultsDuringSolve,
                        onCheckedChange = { viewModel.setHideLastResultsDuringSolve(it) }
                    )
                    SettingsDivider()
                    SettingToggleRow(
                        title = "Hide last results on timer",
                        checked = hideLastResultsOnTimer,
                        onCheckedChange = { viewModel.setHideLastResultsOnTimer(it) }
                    )
                    SettingsDivider()
                    SettingToggleRow(
                        title = "Haptics",
                        checked = hapticsEnabled,
                        onCheckedChange = { viewModel.setHapticsEnabled(it) }
                    )
                    SettingsDivider()
                    SettingExpandableRow(
                        title = "Bottom averages",
                        valueLabel = timerAveragesLabel(timerAverages),
                        expanded = timerAveragesExpanded,
                        onClick = {
                            timerAveragesExpanded = !timerAveragesExpanded
                        }
                    )
                    if (timerAveragesExpanded) {
                        TimerAverageOptions.forEach { average ->
                            SettingsDivider()
                            SettingToggleRow(
                                title = "Show Ao$average",
                                checked = average in timerAverages,
                                onCheckedChange = { viewModel.setTimerAverageEnabled(average, it) }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Defaults") {
                    SettingMenuRow(
                        title = "Default mode",
                        valueLabel = defaultMode.displayName,
                        onClick = { defaultModeMenuExpanded = true },
                        menuExpanded = defaultModeMenuExpanded,
                        onDismissMenu = { defaultModeMenuExpanded = false }
                    ) {
                        Mode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            defaultModeMenuExpanded = false
                            viewModel.setDefaultMode(mode)
                        },
                    )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        content()
    }
}

@Composable
fun SettingSliderRow(
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var lastHapticValue by remember(value) { mutableIntStateOf(value) }
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "${sliderValue.roundToInt()}ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                val currentInt = it.roundToInt()
                if (currentInt != lastHapticValue) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastHapticValue = currentInt
                }
            },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = {
                onValueChangeFinished(sliderValue.roundToInt())
            }
        )
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(title = title) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingExpandableRow(
    title: String,
    valueLabel: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        SettingsRow(title = title) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SettingMenuRow(
    title: String,
    valueLabel: String,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit
) {
    Box {
        Surface(
            onClick = onClick,
            color = androidx.compose.ui.graphics.Color.Transparent
        ) {
            SettingsRow(title = title) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.heightIn(max = 300.dp)
        ) {
            menuContent()
        }
    }
}

val ScrambleScaleOptions = listOf(80, 90, 100, 110, 120, 130, 140)

fun timerAveragesLabel(averages: Set<Int>): String {
    return if (averages.isEmpty()) "None"
    else averages.asSequence().sorted().joinToString(", ") { "Ao$it" }
}
