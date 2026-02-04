package com.maciekhetman.cubetimer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
    val allSolves by viewModel.allSolves.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var defaultModeMenuExpanded by remember { mutableStateOf(false) }
    var importModeMenuExpanded by remember { mutableStateOf(false) }
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
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsSectionTitle(text = "Appearance")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Material You",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Use system dynamic colors",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = dynamicColorEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setDynamicColorEnabled(enabled)
                        }
                    )
                }
            }

            item {
                HorizontalDivider()
            }

            if (!dynamicColorEnabled) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "AMOLED Dark",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Pure black backgrounds in dark theme",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = amoledEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setAmoledEnabled(enabled)
                            }
                        )
                    }
                }

                item {
                    HorizontalDivider()
                }
            }

            item {
                SettingsSectionTitle(text = "Defaults")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Default Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Used when the app starts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        FilledTonalButton(onClick = { defaultModeMenuExpanded = true }) {
                            Text(defaultMode.displayName)
                        }
                        ExpressiveDropdownMenu(
                            expanded = defaultModeMenuExpanded,
                            onDismissRequest = { defaultModeMenuExpanded = false }
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
            }

            item {
                HorizontalDivider()
            }

            item {
                SettingsSectionTitle(text = "Data")
                Text(
                    text = "Import or export data using csTimer session JSON format.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = { exportLauncher.launch("cubetimer-cstimer.json") },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export")
                    }
                    FilledTonalButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "text/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Import Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        FilledTonalButton(onClick = { importModeMenuExpanded = true }) {
                            Text(importMode.displayName)
                        }
                        ExpressiveDropdownMenu(
                            expanded = importModeMenuExpanded,
                            onDismissRequest = { importModeMenuExpanded = false }
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
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
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
                        modifier = Modifier.fillMaxWidth()
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
                        )
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
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}
