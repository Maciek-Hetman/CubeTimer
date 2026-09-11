package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import com.maciekhetman.cubetimer.ui.components.SessionFilterBar
import com.maciekhetman.cubetimer.ui.dialogs.ShareableSolveCardDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.domain.TimeFormatter
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import com.maciekhetman.cubetimer.viewmodel.HistoryUiEffect
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    activeSession: Session? = null,
    isAutomaticMode: Boolean = true,
    onSwitchToAutomatic: () -> Unit = {},
    sessions: List<Session> = emptyList(),
    onSessionSelected: (Session) -> Unit = {},
    onCreateSessionClick: () -> Unit = {},
    onManageSessionsClick: () -> Unit = {},
    syncUiState: SyncUiState = SyncUiState(),
    onSyncClick: () -> Unit = {},
    authState: AuthState = AuthState.Guest,
    onAuthClick: () -> Unit = {},
    onSolveClick: (SolveTime, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 60fps Infinite Scroll Threshold Detection using derivedStateOf
    val shouldLoadMore by remember {
        derivedStateOf {
            if (!uiState.hasMore || uiState.isLoadingMore || uiState.isLoading) {
                false
            } else {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems == 0) false
                else {
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisibleIndex >= totalItems - 10
                }
            }
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadNextPage()
        }
    }

    // Observe one-shot effects from ViewModel
    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is HistoryUiEffect.ShowUndoSnackbar -> {
                    val result = snackbarHostState.showSnackbar(
                        message = effect.message,
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.restoreSolve(effect.solve)
                    }
                }
                is HistoryUiEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
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
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp) // Lift above floating navbar
            )
        },
        topBar = {
            CollapsingTopBar(
                title = "History",
                currentMode = currentMode,
                onModeSelected = onModeSelected,
                scrollBehavior = scrollBehavior,
                activeSession = activeSession,
                isAutomaticMode = isAutomaticMode,
                onSwitchToAutomatic = onSwitchToAutomatic,
                sessions = sessions,
                onSessionSelected = onSessionSelected,
                onCreateSessionClick = onCreateSessionClick,
                onManageSessionsClick = onManageSessionsClick,
                syncUiState = syncUiState,
                onSyncClick = onSyncClick,
                authState = authState,
                onAuthClick = onAuthClick,
                titleBadgeText = if (uiState.totalCount > 0) "${uiState.totalCount}" else null,
                extraActions = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showClearConfirmDialog = true
                        },
                        enabled = uiState.solves.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear History",
                            tint = if (uiState.solves.isNotEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        val topPadding = paddingValues.calculateTopPadding()
        val bottomPadding = paddingValues.calculateBottomPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding)
        ) {
            if (uiState.isLoading && uiState.solves.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp
                    )
                }
            } else if (!uiState.isLoading && uiState.solves.isEmpty()) {
                // Empty state with session filter still accessible
                Column(modifier = Modifier.fillMaxSize()) {
                    SessionFilterBar(
                        currentFilter = uiState.currentFilter,
                        onFilterSelected = { filter -> viewModel.setFilter(filter) },
                        activeSession = activeSession,
                        activeSessionSolvesCount = uiState.activeSessionCount,
                        allSolvesCount = uiState.allSolvesCount,
                        sessions = sessions
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomPadding + 104.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No solves yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Complete solves on the Timer screen to build your session history.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = bottomPadding + 104.dp // 104dp accounts for floating bottom navbar
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Session filter chips bar
                    item(key = "session_filter_bar") {
                        SessionFilterBar(
                            currentFilter = uiState.currentFilter,
                            onFilterSelected = { filter -> viewModel.setFilter(filter) },
                            activeSession = activeSession,
                            activeSessionSolvesCount = uiState.activeSessionCount,
                            allSolvesCount = uiState.allSolvesCount,
                            sessions = sessions
                        )
                    }

                    // Keyed solve card items for 60fps smooth scrolling & animations
                    items(
                        items = uiState.solves,
                        key = { it.id }
                    ) { solve ->
                        val solveIndex = uiState.solves.indexOf(solve)
                        val solveNumber = (uiState.totalCount - solveIndex).coerceAtLeast(1)

                        HistorySolveCard(
                            solve = solve,
                            solveNumber = solveNumber,
                            onClick = {
                                viewModel.selectSolveForDetail(solve)
                                onSolveClick(solve, solveNumber)
                            },
                            onDelete = {
                                viewModel.deleteSolve(solve)
                            },
                            onTogglePlusTwo = {
                                val newPenalty = if (solve.penalty == Penalty.PLUS_TWO) Penalty.NONE else Penalty.PLUS_TWO
                                viewModel.updateSolvePenalty(solve, newPenalty)
                            },
                            onToggleDnf = {
                                val newPenalty = if (solve.penalty == Penalty.DNF) Penalty.NONE else Penalty.DNF
                                viewModel.updateSolvePenalty(solve, newPenalty)
                            },
                            onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        )
                    }

                    // Infinite scroll loading spinner item
                    if (uiState.isLoadingMore) {
                        item(key = "loading_more_footer") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for clearing session history
    if (showClearConfirmDialog) {
        val countToClear = uiState.solves.size
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "Clear History?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to clear all $countToClear solves in this session? You can undo this action immediately after clearing.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        viewModel.clearCurrentFilterHistory()
                        coroutineScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Cleared $countToClear solves",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.undoClearHistory()
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    // Shareable Solve Card Modal Dialog (M4)
    uiState.selectedSolve?.let { detail ->
        ShareableSolveCardDialog(
            detail = detail,
            onDismiss = { viewModel.dismissSolveDetail() }
        )
    }
}

@Composable
private fun HistorySolveCard(
    solve: SolveTime,
    solveNumber: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePlusTwo: () -> Unit,
    onToggleDnf: () -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 14.dp)
        ) {
            // Header: Solve number and overflow menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Solve #$solveNumber",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Box {
                    IconButton(
                        onClick = {
                            onHaptic()
                            menuExpanded = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Solve options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (menuExpanded) {
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (solve.penalty == Penalty.PLUS_TWO) "Remove +2" else "Add +2")
                                },
                                onClick = {
                                    onHaptic()
                                    menuExpanded = false
                                    onTogglePlusTwo()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (solve.penalty == Penalty.DNF) "Remove DNF" else "Add DNF")
                                },
                                onClick = {
                                    onHaptic()
                                    menuExpanded = false
                                    onToggleDnf()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    onHaptic()
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Primary row: Duration and penalty badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = TimeFormatter.formatTime(solve.displayTime),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = when (solve.penalty) {
                        Penalty.DNF -> MaterialTheme.colorScheme.error
                        Penalty.PLUS_TWO -> MaterialTheme.colorScheme.tertiary
                        Penalty.NONE -> MaterialTheme.colorScheme.onSurface
                    }
                )

                if (solve.penalty != Penalty.NONE) {
                    Surface(
                        color = when (solve.penalty) {
                            Penalty.DNF -> MaterialTheme.colorScheme.errorContainer
                            Penalty.PLUS_TWO -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> Color.Transparent
                        },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = when (solve.penalty) {
                                Penalty.DNF -> "DNF"
                                Penalty.PLUS_TWO -> "+2"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            color = when (solve.penalty) {
                                Penalty.DNF -> MaterialTheme.colorScheme.onErrorContainer
                                Penalty.PLUS_TWO -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> Color.Unspecified
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Solve timestamp
            Text(
                text = formatTimestamp(solve.timestamp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Scramble snippet
            if (solve.scramble.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = solve.scramble,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private val timestampFormat by lazy {
    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
}

private fun formatTimestamp(timestamp: Long): String {
    return timestampFormat.format(Date(timestamp))
}
