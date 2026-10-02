package com.maciekhetman.cubetimer.ui.screens

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.domain.TimeFormatter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import com.maciekhetman.cubetimer.ui.components.GroupInnerCorner
import com.maciekhetman.cubetimer.ui.components.GroupSegmentGap
import com.maciekhetman.cubetimer.ui.components.HistoryContextualTopAppBar
import com.maciekhetman.cubetimer.ui.components.SessionCardActions
import com.maciekhetman.cubetimer.ui.components.SessionCardEmptyOrLoadingMessage
import com.maciekhetman.cubetimer.ui.components.SessionCardHeader
import com.maciekhetman.cubetimer.ui.components.SessionCardSolveRow
import com.maciekhetman.cubetimer.ui.components.displaySessionName
import com.maciekhetman.cubetimer.ui.dialogs.HistoryFilterSortBottomSheet
import com.maciekhetman.cubetimer.ui.dialogs.ShareableSolveCardDialog
import com.maciekhetman.cubetimer.viewmodel.HistoryUiEffect
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Export target of an in-flight CreateDocument request, saveable across configuration changes. */
private const val EXPORT_ALL = "all"
private const val EXPORT_SELECTED = "selected"
private const val EXPORT_SESSION_PREFIX = "session:"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier,
    onSolveClick: (SolveTime, Int) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var sessionIdToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteSelectedDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        when {
            target == EXPORT_ALL -> viewModel.exportAllSolves(context, uri)
            target == EXPORT_SELECTED -> viewModel.exportSelectedSolves(context, uri)
            target.startsWith(EXPORT_SESSION_PREFIX) -> {
                val sessionId = target.removePrefix(EXPORT_SESSION_PREFIX)
                uiState.sessionGroups.firstOrNull { it.session.id == sessionId }
                    ?.let { viewModel.exportSession(context, it.session, uri) }
            }
        }
    }
    fun launchExport(target: String, fileName: String) {
        pendingExport = target
        createDocumentLauncher.launch(fileName)
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importSolvesFromUri(context, uri)
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            suspend fun showUndo(message: String, onUndo: () -> Unit) {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) onUndo()
            }
            when (effect) {
                is HistoryUiEffect.ShowUndoSnackbar ->
                    showUndo(effect.message) { viewModel.restoreSolve(effect.solve) }
                is HistoryUiEffect.ShowUndoSessionDelete ->
                    showUndo(resources.getString(R.string.history_session_deleted, displaySessionName(resources, effect.sessionName))) {
                        viewModel.restoreSession(effect.snapshot)
                    }
                is HistoryUiEffect.ShowUndoBatchDelete ->
                    showUndo(effect.message) { viewModel.undoDeleteBatch(effect.deletedSolves) }
                is HistoryUiEffect.ShowUndoClearAll ->
                    showUndo(effect.message) { viewModel.undoDeleteAllSolves(effect.deletedSolves) }
                is HistoryUiEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                // Lift above the floating navbar
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
            )
        },
        topBar = {
            AnimatedContent(
                targetState = uiState.isSelectionMode,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "history_top_bar"
            ) { selectionMode ->
                if (selectionMode) {
                    val visibleSolveIds = uiState.sessionGroups
                        .filter { it.isExpanded }
                        .flatMap { group -> group.solves.map { it.id } }
                    HistoryContextualTopAppBar(
                        selectedCount = uiState.selectedSolveIds.size,
                        isAllSelected = visibleSolveIds.isNotEmpty() &&
                            uiState.selectedSolveIds.containsAll(visibleSolveIds),
                        onDismiss = viewModel::clearSelection,
                        onSelectAllToggle = viewModel::selectAllSolves,
                        onExportSelected = {
                            launchExport(EXPORT_SELECTED, "cubetimer_selected_${System.currentTimeMillis()}.csv")
                        },
                        onDeleteSelected = { showDeleteSelectedDialog = true }
                    )
                } else {
                    CollapsingTopBar(
                        title = stringResource(R.string.nav_history),
                        currentMode = currentMode,
                        onModeSelected = onModeSelected,
                        scrollBehavior = scrollBehavior,
                        extraActions = {
                            IconButton(onClick = { viewModel.openFilterSheet() }) {
                                BadgedBox(
                                    badge = {
                                        if (uiState.totalActiveFilterCount > 0) {
                                            Badge { Text("${uiState.totalActiveFilterCount}") }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.history_filter_sort))
                                }
                            }
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.history_export_all)) },
                                        leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            launchExport(EXPORT_ALL, "cubetimer_all_solves_${System.currentTimeMillis()}.csv")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.history_import)) },
                                        leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            openDocumentLauncher.launch(
                                                arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/*", "*/*")
                                            )
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.history_delete_all), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.DeleteSweep,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            showDeleteAllDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        // 104dp keeps the last item clear of the floating bottom navbar.
        val bottomInset = paddingValues.calculateBottomPadding() + 104.dp
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(top = paddingValues.calculateTopPadding())

        when {
            uiState.isLoading && uiState.sessionGroups.isEmpty() -> Box(
                modifier = contentModifier,
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            uiState.sessionGroups.isEmpty() -> HistoryEmptyState(
                hasActiveFilters = uiState.totalActiveFilterCount > 0,
                onResetFilters = viewModel::resetAllFilters,
                modifier = contentModifier.padding(bottom = bottomInset)
            )

            else -> LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomInset),
                // Small gap within a session's own segments; the larger 16dp gap between sessions
                // is applied as extra top padding on each header below (see headerTopPadding).
                verticalArrangement = Arrangement.spacedBy(GroupSegmentGap)
            ) {
                uiState.sessionGroups.forEachIndexed { groupIndex, group ->
                    val headerTopPadding = if (groupIndex == 0) 0.dp else (16.dp - GroupSegmentGap)

                    // Emitting the header, each visible solve row, and the footer as separate
                    // LazyColumn items (instead of one item per session rendering every solve via
                    // forEachIndexed) keeps large expanded sessions from composing hundreds of
                    // off-screen rows at once.
                    item(key = "${group.session.id}_header") {
                        SessionCardHeader(
                            sessionGroup = group,
                            expanded = group.isExpanded,
                            onClick = { viewModel.toggleSessionExpanded(group.session.id) },
                            modifier = Modifier
                                .animateItem()
                                .padding(top = headerTopPadding)
                        )
                    }

                    if (group.isExpanded) {
                        if (group.solves.isEmpty()) {
                            item(key = "${group.session.id}_empty") {
                                SessionCardEmptyOrLoadingMessage(
                                    sessionGroup = group,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = group.solves,
                                key = { _, solve -> "${group.session.id}_solve_${solve.id}" }
                            ) { index, solve ->
                                SessionCardSolveRow(
                                    sessionGroup = group,
                                    solve = solve,
                                    index = index,
                                    isSelectionMode = uiState.isSelectionMode,
                                    selectedSolveIds = uiState.selectedSolveIds,
                                    onSolveClick = { s, solveNumber ->
                                        viewModel.selectSolveForDetail(s, solveNumber)
                                        onSolveClick(s, solveNumber)
                                    },
                                    onSolveLongClick = { s -> viewModel.startSelection(s.id) },
                                    onToggleSolveSelection = viewModel::toggleSolveSelection,
                                    onTogglePlusTwo = { s ->
                                        viewModel.updateSolvePenalty(
                                            s,
                                            if (s.penalty == Penalty.PLUS_TWO) Penalty.NONE else Penalty.PLUS_TWO
                                        )
                                    },
                                    onToggleDnf = { s ->
                                        viewModel.updateSolvePenalty(
                                            s,
                                            if (s.penalty == Penalty.DNF) Penalty.NONE else Penalty.DNF
                                        )
                                    },
                                    onDeleteSolve = viewModel::deleteSolve,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        item(key = "${group.session.id}_actions") {
                            SessionCardActions(
                                onExport = {
                                    val fileName = group.session.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                                    launchExport(EXPORT_SESSION_PREFIX + group.session.id, "cubetimer_session_$fileName.csv")
                                },
                                onDelete = { sessionIdToDelete = group.session.id },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.sessionGroups.firstOrNull { it.session.id == sessionIdToDelete }?.let { group ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.history_delete_session_title),
            message = stringResource(R.string.history_delete_session_message, displaySessionName(group.session.name)),
            onConfirm = { viewModel.deleteSession(group.session) },
            onDismiss = { sessionIdToDelete = null }
        )
    }

    if (showDeleteSelectedDialog) {
        val count = uiState.selectedSolveIds.size
        ConfirmDeleteDialog(
            title = pluralStringResource(R.plurals.history_delete_selected_title, count, count),
            message = stringResource(R.string.history_delete_selected_message),
            onConfirm = viewModel::deleteSelectedSolves,
            onDismiss = { showDeleteSelectedDialog = false }
        )
    }

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.history_delete_all_title),
            message = stringResource(R.string.history_delete_all_message),
            confirmLabel = stringResource(R.string.history_delete_all_confirm),
            onConfirm = viewModel::deleteAllSolves,
            onDismiss = { showDeleteAllDialog = false }
        )
    }

    if (uiState.isFilterSheetOpen) {
        HistoryFilterSortBottomSheet(
            uiState = uiState,
            onDismissRequest = viewModel::closeFilterSheet,
            onSelectTab = viewModel::setActiveFilterSheetTab,
            onSessionSortChange = viewModel::setSessionSort,
            onPuzzleScopeChange = viewModel::setPuzzleScope,
            onSolveSortChange = viewModel::setSolveSort,
            onPenaltyFilterChange = viewModel::setPenaltyFilter,
            onTimeRangeFilterChange = { viewModel.setTimeRangeFilter(it) },
            onDateRangeFilterChange = { viewModel.setDateRangeFilter(it) },
            onResetAll = viewModel::resetAllFilters
        )
    }

    uiState.selectedSolve?.let { detail ->
        ShareableSolveCardDialog(
            detail = detail,
            onDismiss = viewModel::dismissSolveDetail
        )
    }
}

@Composable
private fun HistoryEmptyState(
    hasActiveFilters: Boolean,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier
                    .padding(20.dp)
                    .size(40.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(if (hasActiveFilters) R.string.history_empty_filtered else R.string.stats_no_solves),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (hasActiveFilters) R.string.history_empty_filtered_hint else R.string.history_empty_hint
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (hasActiveFilters) {
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalButton(onClick = onResetFilters) {
                Text(stringResource(R.string.history_reset_filters))
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.action_delete)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.history_confirm_with_undo, message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * A single solve row inside an expanded session group.
 *
 * Shows the time and "#number · date"; +2 / DNF toggles and delete sit on the right and are
 * swapped for a checkbox (and a read-only penalty label) while multi-selection is active.
 * Test tags: "history_action_plus_two", "history_action_dnf", "history_action_delete".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HistorySolveCard(
    solve: SolveTime,
    solveNumber: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onDelete: () -> Unit,
    onTogglePlusTwo: () -> Unit,
    onToggleDnf: () -> Unit,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    // Selected rows round out, a small shape morph on top of the color change.
    val corner by animateDpAsState(
        targetValue = if (isSelected) 20.dp else GroupInnerCorner,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "solve_row_corner"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "solve_row_color"
    )
    val shape = RoundedCornerShape(corner)

    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (isSelectionMode) null else onLongClick
            )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = TimeFormatter.formatTime(solve.displayTime),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = if (solve.penalty == Penalty.DNF) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (isSelectionMode && solve.penalty != Penalty.NONE) {
                        Text(
                            text = if (solve.penalty == Penalty.DNF) "DNF" else "+2",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (solve.penalty == Penalty.DNF) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            }
                        )
                    }
                }
                Text(
                    text = "#$solveNumber · ${formatTimestamp(solve.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = !isSelectionMode,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PenaltyToggle(
                        label = "+2",
                        selected = solve.penalty == Penalty.PLUS_TWO,
                        onClick = onTogglePlusTwo,
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.testTag("history_action_plus_two")
                    )
                    PenaltyToggle(
                        label = "DNF",
                        selected = solve.penalty == Penalty.DNF,
                        onClick = onToggleDnf,
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.testTag("history_action_dnf")
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("history_action_delete")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Pill toggle that squares off when selected. */
@Composable
private fun PenaltyToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedContainerColor: Color,
    selectedContentColor: Color,
    modifier: Modifier = Modifier
) {
    val corner by animateDpAsState(
        targetValue = if (selected) 10.dp else 18.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "penalty_toggle_corner"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) selectedContainerColor else Color.Transparent,
        label = "penalty_toggle_color"
    )

    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = containerColor,
        contentColor = if (selected) selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// Not cached: the app's language can change while it runs (system per-app language setting).
private fun formatTimestamp(timestamp: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMdjm"), locale).format(Date(timestamp))
}
