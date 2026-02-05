package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.Cube
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.Penalty
import com.maciekhetman.cubetimer.SolveTime
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenu
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenuItem
import com.maciekhetman.cubetimer.ui.components.TopBar
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CubesScreen(
    viewModel: TimerViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val cubes by viewModel.cubes.collectAsState()
    val allSolves by viewModel.allSolves.collectAsState()
    val activeCubeIdByMode by viewModel.activeCubeIdByMode.collectAsState()
    val haptic = LocalHapticFeedback.current

    var showAddCubeDialog by remember { mutableStateOf(false) }
    var cubePendingDelete by remember { mutableStateOf<Cube?>(null) }
    var cubeEditing by remember { mutableStateOf<Cube?>(null) }
    var newCubeBrand by remember { mutableStateOf("") }
    var newCubeModel by remember { mutableStateOf("") }
    var newCubeFeatures by remember { mutableStateOf(setOf<String>()) }
    var newCubeType by remember { mutableStateOf(currentMode) }
    var newCubeTension by remember { mutableStateOf("") }
    var newCubeCenterTravel by remember { mutableStateOf("") }
    var newCubeLubes by remember { mutableStateOf("") }
    var cubeTypeMenuExpanded by remember { mutableStateOf(false) }
    var activeCubeMenuExpanded by remember { mutableStateOf(false) }

    var typeFilterMenuExpanded by remember { mutableStateOf(false) }
    var brandFilterMenuExpanded by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<Mode?>(null) }
    var brandFilter by remember { mutableStateOf<String?>(null) }
    var featureFilters by remember { mutableStateOf(setOf<String>()) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(CubeSortOption.SOLVES_DESC) }

    LaunchedEffect(currentMode, showAddCubeDialog, cubeEditing) {
        if (!showAddCubeDialog && cubeEditing == null) {
            newCubeType = currentMode
        }
    }

    val activeCubeId = activeCubeIdByMode[currentMode]
    val cubesForMode = cubes.filter { it.type == currentMode }
    val activeCube = cubesForMode.firstOrNull { it.id == activeCubeId }
    val activeCubeLabel = activeCube?.displayName ?: "None"
    val activeCubeHint = when {
        cubesForMode.isEmpty() -> "No cubes for this type"
        activeCube == null -> "Choose a cube"
        else -> "Tap to change"
    }

    val brands = cubes.map { it.brand.trim() }
    val knownBrands = brands.filter { it.isNotEmpty() }.distinct().sorted()
    val hasUnknownBrand = brands.any { it.isBlank() }

    val customFeatures = cubes
        .flatMap { it.features }
        .map { it.trim() }
        .filter { it.isNotEmpty() && it !in FeatureOptions }
        .distinct()
        .sorted()
    val featureOptions = FeatureOptions + customFeatures

    val hasFilters = typeFilter != null || brandFilter != null || featureFilters.isNotEmpty()
    val statsByCubeId = remember(cubes, allSolves) {
        cubes.associate { cube ->
            val cubeSolves = allSolves.filter { it.cubeId == cube.id }
            cube.id to calculateCubeStats(cubeSolves)
        }
    }

    val filteredCubes = cubes.filter { cube ->
        val matchesType = typeFilter?.let { cube.type == it } ?: true
        val matchesBrand = when (brandFilter) {
            null -> true
            UnknownBrandFilter -> cube.brand.isBlank()
            else -> cube.brand.equals(brandFilter, ignoreCase = true)
        }
        val matchesFeatures = featureFilters.all { cube.features.contains(it) }
        matchesType && matchesBrand && matchesFeatures
    }.sortedWith(cubeSortComparator(sortOption, statsByCubeId))

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBar(
                title = "Cubes",
                currentMode = currentMode,
                onModeSelected = onModeSelected
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                SectionCard(title = "Manage") {
                    Box {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                activeCubeMenuExpanded = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = SettingsButtonMinHeight),
                            shape = MaterialTheme.shapes.large,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = activeCubeLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = activeCubeHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select cube"
                            )
                        }

                        ExpressiveDropdownMenu(
                            expanded = activeCubeMenuExpanded,
                            onDismissRequest = { activeCubeMenuExpanded = false }
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
                    }

                    CardItemDivider()
                    FilledTonalButton(
                        onClick = {
                            newCubeBrand = ""
                            newCubeModel = ""
                            newCubeFeatures = emptySet()
                            newCubeType = currentMode
                            newCubeTension = ""
                            newCubeCenterTravel = ""
                            newCubeLubes = ""
                            showAddCubeDialog = true
                        },
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
                }
            }

            item {
                SectionCard(
                    title = "Filters",
                    action = if (hasFilters) {
                        {
                            TextButton(
                                onClick = {
                                    typeFilter = null
                                    brandFilter = null
                                    featureFilters = emptySet()
                                }
                            ) {
                                Text("Clear")
                            }
                        }
                    } else {
                        null
                    }
                ) {
                    MenuRow(
                        title = "Type",
                        buttonLabel = typeFilter?.displayName ?: "All types",
                        onClick = { typeFilterMenuExpanded = true },
                        menuExpanded = typeFilterMenuExpanded,
                        onDismissMenu = { typeFilterMenuExpanded = false }
                    ) {
                        ExpressiveDropdownMenuItem(
                            text = { Text("All types") },
                            onClick = {
                                typeFilterMenuExpanded = false
                                typeFilter = null
                            }
                        )
                        Mode.entries.forEach { mode ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    typeFilterMenuExpanded = false
                                    typeFilter = mode
                                }
                            )
                        }
                    }

                    CardItemDivider()
                    MenuRow(
                        title = "Brand",
                        buttonLabel = when (brandFilter) {
                            null -> "All brands"
                            UnknownBrandFilter -> "Unknown"
                            else -> brandFilter ?: "All brands"
                        },
                        onClick = { brandFilterMenuExpanded = true },
                        menuExpanded = brandFilterMenuExpanded,
                        onDismissMenu = { brandFilterMenuExpanded = false }
                    ) {
                        ExpressiveDropdownMenuItem(
                            text = { Text("All brands") },
                            onClick = {
                                brandFilterMenuExpanded = false
                                brandFilter = null
                            }
                        )
                        if (hasUnknownBrand) {
                            ExpressiveDropdownMenuItem(
                                text = { Text("Unknown") },
                                onClick = {
                                    brandFilterMenuExpanded = false
                                    brandFilter = UnknownBrandFilter
                                }
                            )
                        }
                        knownBrands.forEach { brand ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(brand) },
                                onClick = {
                                    brandFilterMenuExpanded = false
                                    brandFilter = brand
                                }
                            )
                        }
                    }

                    CardItemDivider()
                    MenuRow(
                        title = "Sort",
                        buttonLabel = sortOption.label,
                        onClick = { sortMenuExpanded = true },
                        menuExpanded = sortMenuExpanded,
                        onDismissMenu = { sortMenuExpanded = false }
                    ) {
                        CubeSortOption.entries.forEach { option ->
                            ExpressiveDropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sortMenuExpanded = false
                                    sortOption = option
                                }
                            )
                        }
                    }

                    CardItemDivider()
                    Text(
                        text = "Features",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (featureOptions.isEmpty()) {
                        Text(
                            text = "No features added yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            featureOptions.forEach { feature ->
                                val selected = featureFilters.contains(feature)
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        featureFilters = if (selected) {
                                            featureFilters - feature
                                        } else {
                                            featureFilters + feature
                                        }
                                    },
                                    label = { Text(feature) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item {
                if (filteredCubes.isEmpty()) {
                    Text(
                        text = if (cubes.isEmpty()) "No cubes added yet" else "No cubes match filters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (hasFilters) {
                    Text(
                        text = "Showing ${filteredCubes.size} of ${cubes.size} cubes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(filteredCubes, key = { it.id }) { cube ->
                val stats = statsByCubeId[cube.id] ?: calculateCubeStats(emptyList())
                CubeCard(
                    cube = cube,
                    stats = stats,
                    onEdit = { cubeEditing = cube },
                    onDelete = { cubePendingDelete = cube }
                )
            }
        }
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
                    OutlinedTextField(
                        value = newCubeTension,
                        onValueChange = { newCubeTension = it },
                        label = { Text("Tension") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCubeCenterTravel,
                        onValueChange = { newCubeCenterTravel = it },
                        label = { Text("Center Travel") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCubeLubes,
                        onValueChange = { newCubeLubes = it },
                        label = { Text("Lubes (comma-separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Features",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeatureOptions.forEach { feature ->
                            val checked = newCubeFeatures.contains(feature)
                            val toggleFeature: (Boolean) -> Unit = { isChecked ->
                                newCubeFeatures = if (isChecked) {
                                    newCubeFeatures + feature
                                } else {
                                    newCubeFeatures - feature
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = toggleFeature
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = feature,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null
                                )
                            }
                        }
                    }

                    MenuRow(
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
                            features = newCubeFeatures.toList(),
                            tension = newCubeTension,
                            centerTravel = newCubeCenterTravel,
                            lubes = newCubeLubes.split(",")
                        )
                        newCubeBrand = ""
                        newCubeModel = ""
                        newCubeFeatures = emptySet()
                        newCubeType = currentMode
                        newCubeTension = ""
                        newCubeCenterTravel = ""
                        newCubeLubes = ""
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

    if (cubeEditing != null) {
        val cube = cubeEditing!!
        LaunchedEffect(cube.id) {
            newCubeBrand = cube.brand
            newCubeModel = cube.model
            newCubeFeatures = cube.features.toSet()
            newCubeType = cube.type
            newCubeTension = cube.tension
            newCubeCenterTravel = cube.centerTravel
            newCubeLubes = cube.lubes.joinToString(", ")
        }
        AlertDialog(
            onDismissRequest = {
                cubeEditing = null
                newCubeBrand = ""
                newCubeModel = ""
                newCubeFeatures = emptySet()
                newCubeType = currentMode
                newCubeTension = ""
                newCubeCenterTravel = ""
                newCubeLubes = ""
            },
            title = { Text("Edit Cube") },
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
                    OutlinedTextField(
                        value = newCubeTension,
                        onValueChange = { newCubeTension = it },
                        label = { Text("Tension") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCubeCenterTravel,
                        onValueChange = { newCubeCenterTravel = it },
                        label = { Text("Center Travel") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCubeLubes,
                        onValueChange = { newCubeLubes = it },
                        label = { Text("Lubes (comma-separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Features",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeatureOptions.forEach { feature ->
                            val checked = newCubeFeatures.contains(feature)
                            val toggleFeature: (Boolean) -> Unit = { isChecked ->
                                newCubeFeatures = if (isChecked) {
                                    newCubeFeatures + feature
                                } else {
                                    newCubeFeatures - feature
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        role = Role.Checkbox,
                                        onValueChange = toggleFeature
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = feature,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null
                                )
                            }
                        }
                    }

                    MenuRow(
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
                        viewModel.updateCube(
                            cubeId = cube.id,
                            brand = newCubeBrand,
                            model = newCubeModel,
                            type = newCubeType,
                            features = newCubeFeatures.toList(),
                            tension = newCubeTension,
                            centerTravel = newCubeCenterTravel,
                            lubes = newCubeLubes.split(",")
                        )
                        cubeEditing = null
                        newCubeBrand = ""
                        newCubeModel = ""
                        newCubeFeatures = emptySet()
                        newCubeType = currentMode
                        newCubeTension = ""
                        newCubeCenterTravel = ""
                        newCubeLubes = ""
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                FilledTonalButton(
                    onClick = {
                        cubeEditing = null
                        newCubeBrand = ""
                        newCubeModel = ""
                        newCubeFeatures = emptySet()
                        newCubeType = currentMode
                        newCubeTension = ""
                        newCubeCenterTravel = ""
                        newCubeLubes = ""
                    },
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
private fun SectionCard(
    title: String,
    action: (@Composable () -> Unit)? = null,
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    buttonLabel: String,
    onClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    Box {
        FilledTonalButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
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

private data class CubeStats(
    val totalSolves: Int,
    val averageLast50: Long?,
    val meanLast50: Long?,
    val stdDevLast50: Long?
)

private enum class CubeSortOption(val label: String) {
    SOLVES_ASC("Solves: Low to High"),
    SOLVES_DESC("Solves: High to Low"),
    AVG_ASC("Avg (50): Low to High"),
    AVG_DESC("Avg (50): High to Low"),
    MEAN_ASC("Mean (50): Low to High"),
    MEAN_DESC("Mean (50): High to Low")
}

private fun cubeSortComparator(
    option: CubeSortOption,
    statsByCubeId: Map<String, CubeStats>
): Comparator<Cube> {
    return when (option) {
        CubeSortOption.SOLVES_ASC -> compareBy<Cube> { statsByCubeId[it.id]?.totalSolves ?: 0 }
        CubeSortOption.SOLVES_DESC -> compareByDescending<Cube> { statsByCubeId[it.id]?.totalSolves ?: 0 }
        CubeSortOption.AVG_ASC -> Comparator { a, b ->
            compareNullableLong(
                statsByCubeId[a.id]?.averageLast50,
                statsByCubeId[b.id]?.averageLast50,
                descending = false
            )
        }
        CubeSortOption.AVG_DESC -> Comparator { a, b ->
            compareNullableLong(
                statsByCubeId[a.id]?.averageLast50,
                statsByCubeId[b.id]?.averageLast50,
                descending = true
            )
        }
        CubeSortOption.MEAN_ASC -> Comparator { a, b ->
            compareNullableLong(
                statsByCubeId[a.id]?.meanLast50,
                statsByCubeId[b.id]?.meanLast50,
                descending = false
            )
        }
        CubeSortOption.MEAN_DESC -> Comparator { a, b ->
            compareNullableLong(
                statsByCubeId[a.id]?.meanLast50,
                statsByCubeId[b.id]?.meanLast50,
                descending = true
            )
        }
    }
}

private fun compareNullableLong(a: Long?, b: Long?, descending: Boolean): Int {
    if (a == null && b == null) return 0
    if (a == null) return 1
    if (b == null) return -1
    return if (descending) b.compareTo(a) else a.compareTo(b)
}

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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasFeatures = cube.features.isNotEmpty()
    val hasDetails = cube.tension.isNotBlank() || cube.centerTravel.isNotBlank() || cube.lubes.isNotEmpty()
    val hasInfoSection = hasFeatures || hasDetails

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = cube.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = cube.type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (hasInfoSection) {
                CardItemDivider()
                if (hasFeatures) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        cube.features.forEach { feature ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.large
                            ) {
                                Text(
                                text = feature,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                }

                if (hasFeatures && hasDetails) {
                    CardItemDivider()
                }

                if (cube.tension.isNotBlank() || cube.centerTravel.isNotBlank()) {
                    val details = listOf(
                        cube.tension.takeIf { it.isNotBlank() }?.let { "Tension: $it" },
                        cube.centerTravel.takeIf { it.isNotBlank() }?.let { "Center: $it" }
                    ).filterNotNull().joinToString(" • ")
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (cube.lubes.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                        text = "Lubes: ${cube.lubes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            }

            CardItemDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CubeStatCard(
                    label = "Solves",
                    value = stats.totalSolves.toString(),
                    modifier = Modifier.weight(1f)
                )
                CubeStatCard(
                    label = "Avg (50)",
                    value = stats.averageLast50?.let { formatDisplayTime(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
            CardItemDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CubeStatCard(
                    label = "Mean (50)",
                    value = stats.meanLast50?.let { formatDisplayTime(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                CubeStatCard(
                    label = "Std (50)",
                    value = stats.stdDevLast50?.let { formatDisplayTime(it) } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }

            CardItemDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Edit")
                }
                FilledTonalButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun CubeStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CardItemDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
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

private const val UnknownBrandFilter = "__UNKNOWN__"

private val SettingsButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
private val SettingsButtonMinHeight = 48.dp

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
