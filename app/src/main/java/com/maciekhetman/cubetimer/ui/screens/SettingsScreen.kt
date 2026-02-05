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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
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
import com.maciekhetman.cubetimer.Cube
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.Penalty
import com.maciekhetman.cubetimer.SolveTime
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenu
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenuItem
import com.maciekhetman.cubetimer.ui.components.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

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
    val cubes by viewModel.cubes.collectAsState()
    val activeCubeIdByMode by viewModel.activeCubeIdByMode.collectAsState()
    val allSolves by viewModel.allSolves.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var defaultModeMenuExpanded by remember { mutableStateOf(false) }
    var importModeMenuExpanded by remember { mutableStateOf(false) }
    var scrambleScaleMenuExpanded by remember { mutableStateOf(false) }
    var activeCubeMenuExpanded by remember { mutableStateOf(false) }
    var cubeTypeMenuExpanded by remember { mutableStateOf(false) }
    var showAddCubeDialog by remember { mutableStateOf(false) }
    var newCubeBrand by remember { mutableStateOf("") }
    var newCubeModel by remember { mutableStateOf("") }
    var newCubeFeatures by remember { mutableStateOf(setOf<String>()) }
    var newCubeType by remember { mutableStateOf(currentMode) }
    var cubePendingDelete by remember { mutableStateOf<Cube?>(null) }
    var importMode by remember { mutableStateOf(defaultMode) }
    var hasTouchedImportMode by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var showImportChoiceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(defaultMode) {
        if (!hasTouchedImportMode) {
            importMode = defaultMode
        }
    }

    LaunchedEffect(currentMode, showAddCubeDialog) {
        if (!showAddCubeDialog) {
            newCubeType = currentMode
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
                val activeCubeId = activeCubeIdByMode[currentMode]
                val cubesForMode = cubes.filter { it.type == currentMode }
                val activeCubeLabel = cubesForMode.firstOrNull { it.id == activeCubeId }?.displayName ?: "None"

                SettingsSectionCard(
                    title = "Cubes"
                ) {
                    SettingMenuRow(
                        title = "Active Cube",
                        buttonLabel = activeCubeLabel,
                        onClick = { activeCubeMenuExpanded = true },
                        menuExpanded = activeCubeMenuExpanded,
                        onDismissMenu = { activeCubeMenuExpanded = false }
                    ) {
                        ExpressiveDropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                activeCubeMenuExpanded = false
                                viewModel.setActiveCubeForMode(currentMode, null)
                            }
                        )
                        cubesForMode.forEach { cube ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(cube.displayName) },
                                onClick = {
                                    activeCubeMenuExpanded = false
                                    viewModel.setActiveCubeForMode(currentMode, cube.id)
                                }
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = { showAddCubeDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingsButtonMinHeight),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = SettingsButtonPadding
                    ) {
                        Text("Add Cube")
                    }

                    if (cubes.isEmpty()) {
                        Text(
                            text = "No cubes added yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        cubes
                            .sortedWith(compareBy<Cube> { it.type.ordinal }.thenBy { it.displayName })
                            .forEach { cube ->
                                val cubeSolves = allSolves.filter { it.cubeId == cube.id }
                                val stats = calculateCubeStats(cubeSolves)
                                CubeCard(
                                    cube = cube,
                                    stats = stats,
                                    onDelete = { cubePendingDelete = cube }
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

    if (showAddCubeDialog) {
        AlertDialog(
            onDismissRequest = { showAddCubeDialog = false },
            title = { Text("Add Cube") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newCubeBrand,
                        onValueChange = { newCubeBrand = it },
                        label = { Text("Brand") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCubeModel,
                        onValueChange = { newCubeModel = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Features",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FeatureOptions.forEach { feature ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Checkbox(
                                checked = newCubeFeatures.contains(feature),
                                onCheckedChange = { checked ->
                                    newCubeFeatures = if (checked) {
                                        newCubeFeatures + feature
                                    } else {
                                        newCubeFeatures - feature
                                    }
                                }
                            )
                        }
                    }

                    SettingMenuRow(
                        title = "Type",
                        buttonLabel = newCubeType.displayName,
                        onClick = { cubeTypeMenuExpanded = true },
                        menuExpanded = cubeTypeMenuExpanded,
                        onDismissMenu = { cubeTypeMenuExpanded = false }
                    ) {
                        Mode.entries.forEach { mode ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    cubeTypeMenuExpanded = false
                                    newCubeType = mode
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.addCube(
                            brand = newCubeBrand,
                            model = newCubeModel,
                            type = newCubeType,
                            features = newCubeFeatures.toList()
                        )
                        newCubeBrand = ""
                        newCubeModel = ""
                        newCubeFeatures = emptySet()
                        newCubeType = currentMode
                        showAddCubeDialog = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                FilledTonalButton(
                    onClick = { showAddCubeDialog = false },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (cubePendingDelete != null) {
        val cube = cubePendingDelete!!
        AlertDialog(
            onDismissRequest = { cubePendingDelete = null },
            title = { Text("Delete Cube") },
            text = { Text("Delete ${cube.displayName}? This won't delete any solves.") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        viewModel.deleteCube(cube.id)
                        cubePendingDelete = null
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                FilledTonalButton(
                    onClick = { cubePendingDelete = null },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Cancel")
                }
            }
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
private val FeatureOptions = listOf(
    "Maglev",
    "UV coating",
    "Adjustable corner magnets",
    "Dual adjustment system",
    "Ball core",
    "Corner-to-core magnets",
    "Edge magnets",
    "Magnetic core"
)

private data class CubeStats(
    val totalSolves: Int,
    val averageLast50: Long?,
    val meanLast50: Long?,
    val stdDevLast50: Long?
)

private fun calculateCubeStats(solves: List<SolveTime>): CubeStats {
    if (solves.isEmpty()) {
        return CubeStats(0, null, null, null)
    }
    val sorted = solves.sortedBy { it.timestamp }
    val last50 = sorted.takeLast(50)
    val valid = last50.filter { it.penalty != Penalty.DNF }
    if (valid.isEmpty()) {
        return CubeStats(solves.size, null, null, null)
    }
    val times = valid.map { it.displayTime.toDouble() }
    val average = times.average()
    val mean = average
    val variance = times
        .map { value ->
            val diff = value - mean
            diff * diff
        }
        .average()
    val stdDev = sqrt(variance)

    return CubeStats(
        totalSolves = solves.size,
        averageLast50 = average.toLong(),
        meanLast50 = mean.toLong(),
        stdDevLast50 = stdDev.toLong()
    )
}

@Composable
private fun CubeCard(
    cube: Cube,
    stats: CubeStats,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = cube.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = cube.type.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Delete")
                }
            }

            if (cube.features.isNotEmpty()) {
                Text(
                    text = "Features: ${cube.features.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CubeStatItem(
                    label = "Solves",
                    value = stats.totalSolves.toString(),
                    modifier = Modifier.weight(1f)
                )
                CubeStatItem(
                    label = "Avg (50)",
                    value = stats.averageLast50?.let { formatDisplayTime(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CubeStatItem(
                    label = "Mean (50)",
                    value = stats.meanLast50?.let { formatDisplayTime(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                CubeStatItem(
                    label = "Std (50)",
                    value = stats.stdDevLast50?.let { formatDisplayTime(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CubeStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatDisplayTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val milliseconds = (millis % 1000) / 10

    return if (totalSeconds >= 60) {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        String.format("%d:%02d.%02d", minutes, seconds, milliseconds)
    } else {
        String.format("%d.%02d", totalSeconds, milliseconds)
    }
}
