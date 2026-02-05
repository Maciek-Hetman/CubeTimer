package com.maciekhetman.cubetimer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenu
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenuItem
import com.maciekhetman.cubetimer.ui.components.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val allSolves by viewModel.allSolves.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopBar(
                title = "Settings",
                currentMode = currentMode,
                onModeSelected = onModeSelected
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                SettingsSectionCard(
                    title = "Appearance"
                ) {
                    SettingToggleRow(
                        title = "Material You",
                        checked = dynamicColorEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDynamicColorEnabled(enabled)
                        }
                    )

                    if (!dynamicColorEnabled) {
                        SettingToggleRow(
                            title = "AMOLED Dark",
                            checked = amoledEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setAmoledEnabled(enabled)
                            }
                        )
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Scramble"
                ) {
                    SettingToggleRow(
                        title = "Show New Scramble Button",
                        checked = showScrambleRefreshButton,
                        onCheckedChange = { show ->
                            viewModel.setShowScrambleRefreshButton(show)
                        }
                    )

                    SettingMenuRow(
                        title = "Scramble Size",
                        buttonLabel = "${scrambleScalePercent}%",
                        onClick = { scrambleScaleMenuExpanded = true },
                        menuExpanded = scrambleScaleMenuExpanded,
                        onDismissMenu = { scrambleScaleMenuExpanded = false }
                    ) {
                        ScrambleScaleOptions.forEach { percent ->
                            ExpressiveDropdownMenuItem(
                                text = { Text("$percent%") },
                                onClick = {
                                    scrambleScaleMenuExpanded = false
                                    viewModel.setScrambleScalePercent(percent)
                                }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Defaults"
                ) {
                    SettingMenuRow(
                        title = "Default Mode",
                        buttonLabel = defaultMode.displayName,
                        onClick = { defaultModeMenuExpanded = true },
                        menuExpanded = defaultModeMenuExpanded,
                        onDismissMenu = { defaultModeMenuExpanded = false }
                    ) {
                        Mode.entries.forEach { mode ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    defaultModeMenuExpanded = false
                                    viewModel.setDefaultMode(mode)
                                }
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionCard(
                    title = "Data"
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingsButtonMinHeight),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { exportLauncher.launch("cubetimer-cstimer.json") },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = SettingsButtonMinHeight),
                            contentPadding = SettingsButtonPadding
                        ) {
                            Text("Export")
                        }
                        FilledTonalButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "text/*"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = SettingsButtonMinHeight),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = SettingsButtonPadding
                        ) {
                            Text("Import")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingMenuRow(
                        title = "Import Mode",
                        buttonLabel = importMode.displayName,
                        onClick = { importModeMenuExpanded = true },
                        menuExpanded = importModeMenuExpanded,
                        onDismissMenu = { importModeMenuExpanded = false }
                    ) {
                        Mode.entries.forEach { mode ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingsButtonMinHeight),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = SettingsButtonPadding
                    ) {
                        Text("Add to existing data")
                    }
                    FilledTonalButton(
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        onClick = {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingsButtonMinHeight),
                        contentPadding = SettingsButtonPadding
                    ) {
                        Text("Replace existing data")
                    }
                    FilledTonalButton(
                        onClick = {
                            showImportChoiceDialog = false
                            pendingImportJson = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = SettingsButtonPadding
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
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingMenuRow(
    title: String,
    buttonLabel: String,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box {
            FilledTonalButton(
                onClick = onClick,
                modifier = Modifier.heightIn(min = SettingsButtonMinHeight),
                contentPadding = SettingsButtonPadding
            ) {
                Text(buttonLabel)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Open menu"
                )
            }

            ExpressiveDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = onDismissMenu
            ) {
                menuContent()
            }
        }
    }
}

private val SettingsButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
private val SettingsButtonMinHeight = 48.dp
private val ScrambleScaleOptions = listOf(80, 90, 100, 110, 120, 130, 140)
