package com.maciekhetman.cubetimer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TimerViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsState()
    val defaultMode by viewModel.defaultMode.collectAsState()
    val amoledEnabled by viewModel.amoledEnabled.collectAsState()
    val showScrambleRefreshButton by viewModel.showScrambleRefreshButton.collectAsState()
    val scrambleScalePercent by viewModel.scrambleScalePercent.collectAsState()
    val timerStartDelayMillis by viewModel.timerStartDelayMillis.collectAsState()
    val allSolves by viewModel.allSolves.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    var defaultModeMenuExpanded by remember { mutableStateOf(false) }
    var importModeMenuExpanded by remember { mutableStateOf(false) }
    var scrambleScaleMenuExpanded by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf(defaultMode) }
    var hasTouchedImportMode by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var showImportChoiceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(defaultMode) {
        if (!hasTouchedImportMode) {
            importMode = defaultMode
        }
    }


    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.exportSolvesAsCsTimerJson()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray())
                    }
                }
            }
            if (result.isFailure) {
                snackbarHostState.showSnackbar(
                    message = "Export failed",
                    duration = SnackbarDuration.Short
                )
            } else {
                snackbarHostState.showSnackbar(
                    message = "Exported csTimer file",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val textResult = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                }
            }

            val text = textResult.getOrNull().orEmpty()
            val detected = viewModel.detectCsTimerMode(text)
            if (detected != null && detected != importMode) {
                snackbarHostState.showSnackbar(
                    message = "Detected ${detected.displayName} data. Select that mode to import.",
                    duration = SnackbarDuration.Short
                )
                return@launch
            }

            if (allSolves.isNotEmpty()) {
                pendingImportJson = text
                showImportChoiceDialog = true
            } else {
                val importResult = viewModel.importSolvesFromCsTimerJson(
                    json = text,
                    fallbackMode = importMode,
                    replaceExisting = false
                )
                if (importResult.isSuccess) {
                    val count = importResult.getOrDefault(0)
                    snackbarHostState.showSnackbar(
                        message = "Imported $count solve(s)",
                        duration = SnackbarDuration.Short
                    )
                } else {
                    snackbarHostState.showSnackbar(
                        message = importResult.exceptionOrNull()?.message ?: "Import failed",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CollapsingTopBar(
                title = "Settings",
                currentMode = currentMode,
                onModeSelected = onModeSelected,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSection(title = "Appearance") {
                    SettingToggleRow(
                        title = "Dynamic color",
                        checked = dynamicColorEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDynamicColorEnabled(enabled)
                        }
                    )
                    if (!dynamicColorEnabled) {
                        SettingsDivider()
                        SettingToggleRow(
                            title = "AMOLED dark",
                            checked = amoledEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setAmoledEnabled(enabled)
                            }
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "Scramble") {
                    SettingToggleRow(
                        title = "New scramble button",
                        checked = showScrambleRefreshButton,
                        onCheckedChange = { show ->
                            viewModel.setShowScrambleRefreshButton(show)
                        }
                    )
                    SettingsDivider()
                    SettingMenuRow(
                        title = "Scramble size",
                        valueLabel = "${scrambleScalePercent}%",
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
                                }
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
                                }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Data") {
                    SettingActionRow(
                        title = "Export solves",
                        onClick = { exportLauncher.launch("cubetimer-cstimer.json") }
                    )
                    SettingsDivider()
                    SettingActionRow(
                        title = "Import solves",
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "text/*"))
                        }
                    )
                    SettingsDivider()
                    SettingMenuRow(
                        title = "Import mode",
                        valueLabel = importMode.displayName,
                        onClick = { importModeMenuExpanded = true },
                        menuExpanded = importModeMenuExpanded,
                        onDismissMenu = { importModeMenuExpanded = false }
                    ) {
                        Mode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    importModeMenuExpanded = false
                                    importMode = mode
                                    hasTouchedImportMode = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportChoiceDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportChoiceDialog = false
                pendingImportJson = null
            },
            title = { Text("Import Solves") },
            text = { Text("You already have saved solves. Do you want to add to existing data or replace it?") },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val json = pendingImportJson ?: return@FilledTonalButton
                            showImportChoiceDialog = false
                            pendingImportJson = null
                            scope.launch {
                                val importResult = viewModel.importSolvesFromCsTimerJson(
                                    json = json,
                                    fallbackMode = importMode,
                                    replaceExisting = false
                                )
                                if (importResult.isSuccess) {
                                    val count = importResult.getOrDefault(0)
                                    snackbarHostState.showSnackbar(
                                        message = "Imported $count solve(s)",
                                        duration = SnackbarDuration.Short
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(
                                        message = importResult.exceptionOrNull()?.message ?: "Import failed",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add to existing data")
                    }
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val json = pendingImportJson ?: return@FilledTonalButton
                            showImportChoiceDialog = false
                            pendingImportJson = null
                            scope.launch {
                                val importResult = viewModel.importSolvesFromCsTimerJson(
                                    json = json,
                                    fallbackMode = importMode,
                                    replaceExisting = true
                                )
                                if (importResult.isSuccess) {
                                    val count = importResult.getOrDefault(0)
                                    snackbarHostState.showSnackbar(
                                        message = "Replaced with $count solve(s)",
                                        duration = SnackbarDuration.Short
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(
                                        message = importResult.exceptionOrNull()?.message ?: "Import failed",
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Replace existing data")
                    }
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showImportChoiceDialog = false
                            pendingImportJson = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
            },
            dismissButton = {}
        )
    }

}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider()
}

@Composable
private fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    trailingContent: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        )
        trailingContent()
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }
    val roundedValue = ((sliderValue / 100f).roundToInt() * 100)
        .coerceIn(valueRange.start.toInt(), valueRange.endInclusive.toInt())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                text = "${roundedValue} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { rawValue ->
                sliderValue = rawValue
            },
            onValueChangeFinished = {
                sliderValue = roundedValue.toFloat()
                onValueChangeFinished(roundedValue)
            },
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    SettingsRow(
        title = title,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = { enabled ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCheckedChange(enabled)
                }
            )
        }
    )
}

@Composable
private fun SettingActionRow(
    title: String,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    SettingsRow(
        title = title,
        trailingContent = {
            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ) {
                Text(title.substringBefore(" "))
            }
        }
    )
}

@Composable
private fun SettingMenuRow(
    title: String,
    valueLabel: String,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    SettingsRow(
        title = title,
        trailingContent = {
            Box {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 8.dp,
                        end = 8.dp,
                        bottom = 8.dp
                    )
                ) {
                    Text(
                        text = valueLabel,
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Open menu"
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onDismissMenu,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    menuContent()
                }
            }
        }
    )
}

private val ScrambleScaleOptions = listOf(80, 90, 100, 110, 120, 130, 140)


