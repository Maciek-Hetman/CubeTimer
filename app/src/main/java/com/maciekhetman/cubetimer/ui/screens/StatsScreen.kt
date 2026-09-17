package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maciekhetman.cubetimer.domain.AverageCalculator
import com.maciekhetman.cubetimer.domain.TimeFormatter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.ui.components.ActivityTracker
import com.maciekhetman.cubetimer.ui.components.SectionHeader
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import com.maciekhetman.cubetimer.ui.components.SessionFilterBar
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.StatsFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: TimerViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    activeSession: Session? = null,
    isAutomaticMode: Boolean = true,
    onSwitchToAutomatic: () -> Unit = {},
    sessions: List<Session> = emptyList(),
    onSessionSelected: (Session) -> Unit = {},
    onCreateSessionClick: () -> Unit = {},
    onManageSessionsClick: () -> Unit = {},
    hideSessionMenu: Boolean = false,
    modifier: Modifier = Modifier
) {
    val solves by viewModel.solves.collectAsStateWithLifecycle()
    val filteredSolves by viewModel.statsFilteredSolves.collectAsStateWithLifecycle()
    val statsFilter by viewModel.statsFilter.collectAsStateWithLifecycle()
    val currentActiveSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val effectiveActiveSession = activeSession ?: currentActiveSession
    val appTimeMillis by viewModel.appTimeMillis.collectAsStateWithLifecycle()
    val hideSessionMenuInTopBar by viewModel.hideSessionMenuInTopBar.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val layoutDirection = LocalLayoutDirection.current
    val haptic = LocalHapticFeedback.current

    val activeSessionSolvesCount = remember(solves, effectiveActiveSession) {
        val activeId = effectiveActiveSession?.id
        if (activeId != null) solves.count { it.sessionId == activeId } else solves.size
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CollapsingTopBar(
                title = "Statistics",
                currentMode = currentMode,
                onModeSelected = onModeSelected,
                scrollBehavior = scrollBehavior,
                activeSession = effectiveActiveSession,
                isAutomaticMode = isAutomaticMode,
                onSwitchToAutomatic = onSwitchToAutomatic,
                sessions = sessions,
                onSessionSelected = onSessionSelected,
                onCreateSessionClick = onCreateSessionClick,
                onManageSessionsClick = onManageSessionsClick,
                hideSessionMenu = hideSessionMenu || hideSessionMenuInTopBar
            )
        }
    ) { paddingValues ->
        val top = paddingValues.calculateTopPadding()
        val bottom = paddingValues.calculateBottomPadding()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = top + 8.dp,
                end = 16.dp,
                bottom = bottom + 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Session Filter Chips Bar
            item {
                SessionFilterBar(
                    currentFilter = statsFilter,
                    onFilterSelected = { viewModel.setStatsFilter(it) },
                    activeSession = effectiveActiveSession,
                    activeSessionSolvesCount = activeSessionSolvesCount,
                    allSolvesCount = solves.size,
                    sessions = sessions
                )
            }

            if (filteredSolves.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (solves.isEmpty()) "No solves yet" else "No solves in selected filter",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item {
                    val allTimePb = remember(solves) {
                        solves.filter { it.penalty != Penalty.DNF }.minByOrNull { it.displayTime }
                    }
                    val sessionSolves = remember(solves, filteredSolves, statsFilter, effectiveActiveSession) {
                        if (statsFilter is StatsFilter.AllSessions && effectiveActiveSession != null) {
                            solves.filter { it.sessionId == effectiveActiveSession.id }
                        } else {
                            filteredSolves
                        }
                    }
                    val sessionAo5 = remember(sessionSolves) { AverageCalculator.averageOfN(sessionSolves, 5) }
                    val sessionAo12 = remember(sessionSolves) { AverageCalculator.averageOfN(sessionSolves, 12) }

                    StatsHeroCard(
                        allTimePb = allTimePb,
                        sessionAo5 = sessionAo5,
                        sessionAo12 = sessionAo12
                    )
                }

                item {
                    CompactSummaryGrid(solves = filteredSolves)
                }

                item {
                    ChartsSection(solves = filteredSolves)
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        ActivityTracker(solves = filteredSolves)
                    }
                }

                item {
                    LargeAveragesSection(solves = filteredSolves)
                }

                item {
                    SessionMetricsSection(solves = filteredSolves, appTimeMillis = appTimeMillis)
                }

                item {
                    PenaltyStatsSection(solves = filteredSolves)
                }
            }
        }
    }
}


@Composable
private fun ChartsSection(solves: List<SolveTime>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = "Charts")

        PersonalBestsChart(solves = solves)
        SolveTimesChart(solves = solves)
        AveragesChart(solves = solves)
    }
}

// =============================================================================
// Top Hero Card Component
// =============================================================================

@Composable
fun StatsHeroCard(
    allTimePb: SolveTime?,
    sessionAo5: Long?,
    sessionAo12: Long?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Label + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PERSONAL BEST",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                if (allTimePb != null) {
                    Text(
                        text = formatTimestamp(allTimePb.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Time Display with optional +2 badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (allTimePb != null) formatTime(allTimePb.displayTime) else "--",
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (allTimePb?.penalty == Penalty.PLUS_TWO) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "+2",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Scramble Box
            if (allTimePb != null && allTimePb.scramble.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = allTimePb.scramble,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Current Session Averages Pills Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SessionAveragePill(
                    label = "Current Ao5",
                    time = sessionAo5,
                    modifier = Modifier.weight(1f)
                )
                SessionAveragePill(
                    label = "Current Ao12",
                    time = sessionAo12,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun StatsHeroCard(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    val allTimePb = remember(solves) {
        solves.filter { it.penalty != Penalty.DNF }.minByOrNull { it.displayTime }
    }
    val sessionAo5 = remember(solves) { AverageCalculator.averageOfN(solves, 5) }
    val sessionAo12 = remember(solves) { AverageCalculator.averageOfN(solves, 12) }
    StatsHeroCard(
        allTimePb = allTimePb,
        sessionAo5 = sessionAo5,
        sessionAo12 = sessionAo12,
        modifier = modifier
    )
}

@Composable
private fun SessionAveragePill(
    label: String,
    time: Long?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (time != null) formatTime(time) else "--",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (time != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// =============================================================================
// Compact Summary Grid Component (2x2: Ao5, Ao12, Ao50, Ao100)
// =============================================================================

data class StandardAverageData(
    val label: String,
    val count: Int,
    val current: Long?,
    val best: Long?
)

@Composable
fun CompactSummaryGrid(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    val items = remember(solves) {
        listOf(
            StandardAverageData("Ao5", 5, AverageCalculator.averageOfN(solves, 5), AverageCalculator.bestAverageOfN(solves, 5)),
            StandardAverageData("Ao12", 12, AverageCalculator.averageOfN(solves, 12), AverageCalculator.bestAverageOfN(solves, 12)),
            StandardAverageData("Ao50", 50, AverageCalculator.averageOfN(solves, 50), AverageCalculator.bestAverageOfN(solves, 50)),
            StandardAverageData("Ao100", 100, AverageCalculator.averageOfN(solves, 100), AverageCalculator.bestAverageOfN(solves, 100))
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(title = "Standard Averages")

        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    StandardAverageCard(
                        item = item,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StandardAverageCard(
    item: StandardAverageData,
    modifier: Modifier = Modifier
) {
    val isCurrentPb = item.current != null && item.best != null && item.current == item.best

    Surface(
        modifier = modifier.heightIn(min = 96.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isCurrentPb) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "PB",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = if (item.current != null) formatTime(item.current) else "--",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (item.current != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PB",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (item.best != null) formatTime(item.best) else "--",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.best != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// =============================================================================
// Collapsible Section Architecture
// =============================================================================

@Composable
private fun CollapsibleSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    badgeText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "arrow_rotation"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (badgeText != null && !isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                        modifier = Modifier.rotate(rotationAngle),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(animationSpec = tween(durationMillis = 150)) +
                        expandVertically(
                            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ),
                exit = fadeOut(animationSpec = tween(durationMillis = 150)) +
                       shrinkVertically(
                           animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                           shrinkTowards = Alignment.Top
                       )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    }
}

// =============================================================================
// Collapsible Large Averages Section (Ao500, Ao1000, Ao2000)
// =============================================================================

private val LargeAverages = listOf(
    500 to "Ao500",
    1000 to "Ao1000",
    2000 to "Ao2000"
)

@Composable
private fun LargeAveragesSection(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    // Ao500/Ao1000/Ao2000 involve a bestAverageOfN scan over the whole solve list; only compute
    // them (and only off the main thread) once the section is actually expanded and visible.
    val averagesData by produceState<Map<Int, Pair<Long?, Long?>>?>(initialValue = null, solves, isExpanded) {
        value = if (isExpanded) {
            withContext(Dispatchers.Default) {
                LargeAverages.associate { (count, _) ->
                    count to (AverageCalculator.averageOfN(solves, count) to AverageCalculator.bestAverageOfN(solves, count))
                }
            }
        } else {
            null
        }
    }

    val badgeText = remember(averagesData) {
        val ao500 = averagesData?.get(500)?.first
        if (ao500 != null) "Ao500: ${formatTime(ao500)}" else "Ao500 • Ao1000 • Ao2000"
    }

    CollapsibleSectionCard(
        title = "Large Averages",
        badgeText = badgeText,
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
        modifier = modifier
    ) {
        val data = averagesData
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            return@CollapsibleSectionCard
        }
        LargeAverages.forEach { (count, label) ->
            val (current, best) = data.getValue(count)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (solves.size < count) "Requires $count solves (${solves.size}/$count)" else "Window: $count solves",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (current != null) formatTime(current) else "N/A",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (best != null) {
                            Text(
                                text = "PB: ${formatTime(best)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// Collapsible Session & Detailed Metrics Section
// =============================================================================

private data class SessionMetricsData(
    val sessionStats: SessionStats?,
    val allTimeMean: Long,
    val allTimeStdDev: Double
)

@Composable
private fun SessionMetricsSection(
    solves: List<SolveTime>,
    appTimeMillis: Long,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    // Cheap enough to keep synchronous: needed for the badge even while collapsed.
    val totalSolvingTime = remember(solves) { solves.sumOf { it.timeInMillis } }
    val badgeText = remember(solves, totalSolvingTime) {
        "${solves.size} solves • ${TimeFormatter.formatDuration(totalSolvingTime)}"
    }

    // Session bucketing + mean/std-dev are only needed once the section is expanded; compute
    // them off the main thread so opening this section never freezes the UI.
    val metrics by produceState<SessionMetricsData?>(initialValue = null, solves, isExpanded) {
        value = if (isExpanded) {
            withContext(Dispatchers.Default) {
                val allValidSolves = solves.filter { it.penalty != Penalty.DNF }
                SessionMetricsData(
                    sessionStats = calculateSessionStats(solves),
                    allTimeMean = AverageCalculator.mean(allValidSolves),
                    allTimeStdDev = AverageCalculator.standardDeviation(allValidSolves)
                )
            }
        } else {
            null
        }
    }

    CollapsibleSectionCard(
        title = "Session & Detailed Metrics",
        badgeText = badgeText,
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
        modifier = modifier
    ) {
        val data = metrics
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            return@CollapsibleSectionCard
        }
        val sessionStats = data.sessionStats
        val allTimeMean = data.allTimeMean
        val allTimeStdDev = data.allTimeStdDev
        if (sessionStats != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    label = "Session Best",
                    value = formatTime(sessionStats.bestSessionTime),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                StatCard(
                    label = "Session Worst",
                    value = formatTime(sessionStats.worstSessionTime),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    label = "Session Avg",
                    value = formatTime(sessionStats.sessionAverage),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                StatCard(
                    label = "Mean Solve",
                    value = formatTime(sessionStats.meanSolveTime),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    label = "Session Std. Dev",
                    value = formatTime(sessionStats.standardDeviation.toLong()),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                StatCard(
                    label = "Time Cubing",
                    value = TimeFormatter.formatDuration(sessionStats.avgTimeCubingInSession),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                label = "Total Solves",
                value = "${solves.size}",
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            StatCard(
                label = "All-Time Mean",
                value = formatTime(allTimeMean),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                label = "Std. Deviation",
                value = formatTime(allTimeStdDev.toLong()),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            StatCard(
                label = "Time Solving",
                value = TimeFormatter.formatDuration(totalSolvingTime),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(
                label = "Time in App",
                value = TimeFormatter.formatDuration(appTimeMillis),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// =============================================================================
// Collapsible Penalty Distributions Section
// =============================================================================

private data class PenaltyDistributionData(
    val dnfCount: Int,
    val plusTwoCount: Int,
    val dnfPercent: Int,
    val plusTwoPercent: Int,
    val cleanCount: Int,
    val cleanPercent: Int
)

@Composable
private fun PenaltyStatsSection(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val (dnfCount, plusTwoCount, dnfPercent, plusTwoPercent, cleanCount, cleanPercent) = remember(solves) {
        val dnf = solves.count { it.penalty == Penalty.DNF }
        val plusTwo = solves.count { it.penalty == Penalty.PLUS_TWO }
        val total = solves.size.toFloat()
        val dnfPct = if (total > 0) (dnf / total * 100).toInt() else 0
        val plusTwoPct = if (total > 0) (plusTwo / total * 100).toInt() else 0
        val clean = solves.size - dnf - plusTwo
        val cleanPct = if (total > 0) (clean / total * 100).toInt() else 100
        PenaltyDistributionData(dnf, plusTwo, dnfPct, plusTwoPct, clean, cleanPct)
    }

    val badgeText = remember(dnfCount, plusTwoCount, cleanPercent) {
        if (dnfCount + plusTwoCount == 0) "100% clean (0 penalties)"
        else "DNF: $dnfCount ($dnfPercent%) • +2: $plusTwoCount ($plusTwoPercent%)"
    }

    CollapsibleSectionCard(
        title = "Penalty Distribution",
        badgeText = badgeText,
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PenaltyCard(
                label = "DNF",
                percentage = dnfPercent,
                count = dnfCount,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
            PenaltyCard(
                label = "+2",
                percentage = plusTwoPercent,
                count = plusTwoCount,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        }
    }
}

@Composable
private fun PersonalBestsChart(solves: List<SolveTime>) {
    var selectedRange by remember { mutableStateOf("All") }
    val haptic = LocalHapticFeedback.current
    val validSolves = remember(solves) { solves.filter { it.penalty != Penalty.DNF } }

    if (validSolves.isEmpty()) {
        return
    }

    // calculatePersonalBests walks every solve computing rolling Ao5/Ao12 windows; keep it off
    // the main thread so this always-visible chart never blocks composition.
    val pbData by produceState<PersonalBestsData?>(initialValue = null, solves) {
        value = withContext(Dispatchers.Default) { calculatePersonalBests(solves) }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "Personal Best Progress")

            val data = pbData
            if (data == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val singlePBs = data.singlePBs
            val ao5PBs = data.ao5PBs
            val ao12PBs = data.ao12PBs

            val singleColor = MaterialTheme.colorScheme.primary
            val ao5Color = MaterialTheme.colorScheme.secondary
            val ao12Color = MaterialTheme.colorScheme.tertiary
            val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

            val rangeStartIndex = rangeStartIndex(solves.size, selectedRange)
            val rangeEndIndex = (solves.size - 1).coerceAtLeast(0)
            fun rangePbs(pbs: List<Pair<Int, Long>>): List<Pair<Int, Long>> {
                if (rangeStartIndex <= 0) return pbs
                val previous = pbs.lastOrNull { it.first < rangeStartIndex }
                val inside = pbs.filter { it.first >= rangeStartIndex }
                return if (previous != null) listOf(rangeStartIndex to previous.second) + inside else inside
            }

            val visibleSinglePBs = rangePbs(singlePBs)
            val visibleAo5PBs = rangePbs(ao5PBs)
            val visibleAo12PBs = rangePbs(ao12PBs)
            val allPBs = (
                visibleSinglePBs.map { it.second } +
                    visibleAo5PBs.map { it.second } +
                    visibleAo12PBs.map { it.second }
                )
            
            if (allPBs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No personal bests yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurfaceVariant
                    )
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    val padding = 40f
                    
                    val minValue = allPBs.minOrNull() ?: 0L
                    val maxValue = allPBs.maxOrNull() ?: 1L
                    val range = (maxValue - minValue).coerceAtLeast(1L)
                    // Add 10% padding to the range to ensure lines don't overlap with axes
                    val displayRange = range * 1.1f
                    val displayMin = minValue - (range * 0.05f).toLong()
                    val indexRange = (rangeEndIndex - rangeStartIndex).coerceAtLeast(1)
                    
                    // Helper function to draw PB line
                    fun drawPBLine(pbs: List<Pair<Int, Long>>, color: Color) {
                        if (pbs.isEmpty()) return
                        
                        val path = Path()
                        var firstPoint = true
                        
                        // Draw line through all PB points
                        pbs.forEach { (solveIndex, pbTime) ->
                            val x = padding + ((solveIndex - rangeStartIndex).toFloat() / indexRange) * (width - 2 * padding)
                            val y = height - padding - ((pbTime - displayMin).toFloat() / displayRange) * (height - 2 * padding)
                            
                            if (firstPoint) {
                                path.moveTo(x, y)
                                firstPoint = false
                            } else {
                                path.lineTo(x, y)
                            }
                            // Draw point
                            drawCircle(color, radius = 5f, center = Offset(x, y))
                        }
                        
                        // Extend line to the end of the chart (current PB holds)
                        val lastPB = pbs.last()
                        val lastX = padding + ((lastPB.first - rangeStartIndex).toFloat() / indexRange) * (width - 2 * padding)
                        val lastY = height - padding - ((lastPB.second - displayMin).toFloat() / displayRange) * (height - 2 * padding)
                        val endX = width - padding
                        
                        if (lastX < endX) {
                            path.lineTo(endX, lastY)
                        }
                        
                        drawPath(path, color, style = Stroke(width = 3f))
                    }
                    
                    // Draw all three PB lines
                    drawPBLine(visibleAo12PBs, ao12Color)
                    drawPBLine(visibleAo5PBs, ao5Color)
                    drawPBLine(visibleSinglePBs, singleColor)
                    
                    // Draw axes
                    drawLine(
                        onSurfaceVariant,
                        Offset(padding, height - padding),
                        Offset(width - padding, height - padding),
                        strokeWidth = 2f
                    )
                    drawLine(
                        onSurfaceVariant,
                        Offset(padding, padding),
                        Offset(padding, height - padding),
                        strokeWidth = 2f
                    )
                }

                ChartRangeSelector(
                    selectedRange = selectedRange,
                    onRangeSelected = { range ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedRange = range
                    }
                )
                
                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(12.dp),
                                    color = singleColor,
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {}
                                Text(
                                    text = "Single",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(12.dp),
                                    color = ao5Color,
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {}
                                Text(
                                    text = "Ao5",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(12.dp),
                                    color = ao12Color,
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {}
                                Text(
                                    text = "Ao12",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SolveTimesChart(solves: List<SolveTime>) {
    var selectedRange by remember { mutableStateOf("All") }
    val haptic = LocalHapticFeedback.current
    val rangeStartIndex = rangeStartIndex(solves.size, selectedRange)
    val visibleSolves = remember(solves, selectedRange) {
        solves
            .mapIndexed { index, solve -> index to solve }
            .filter { (index, solve) -> index >= rangeStartIndex && solve.penalty != Penalty.DNF }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "Solve Times")

            if (visibleSolves.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Complete more solves to see solve times",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val smoothed = remember(visibleSolves) {
                    visibleSolves.mapIndexed { visibleIndex, (solveIndex, _) ->
                        val windowStart = (visibleIndex - 2).coerceAtLeast(0)
                        val windowEnd = (visibleIndex + 2).coerceAtMost(visibleSolves.lastIndex)
                        val average = visibleSolves
                            .subList(windowStart, windowEnd + 1)
                            .map { it.second.displayTime }
                            .average()
                            .toLong()
                        solveIndex to average
                    }
                }
                val values = remember(smoothed) { smoothed.map { it.second } }
                val lineColor = MaterialTheme.colorScheme.primary
                val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                ) {
                    val width = size.width
                    val height = size.height
                    val padding = 40f
                    val minValue = values.minOrNull() ?: 0L
                    val maxValue = values.maxOrNull() ?: 1L
                    val range = (maxValue - minValue).coerceAtLeast(1L)
                    val displayRange = range * 1.1f
                    val displayMin = minValue - (range * 0.05f).toLong()
                    val rangeEndIndex = (solves.size - 1).coerceAtLeast(rangeStartIndex + 1)
                    val indexRange = (rangeEndIndex - rangeStartIndex).coerceAtLeast(1)
                    val path = Path()

                    smoothed.forEachIndexed { index, (solveIndex, time) ->
                        val x = padding + ((solveIndex - rangeStartIndex).toFloat() / indexRange) * (width - 2 * padding)
                        val y = height - padding - ((time - displayMin).toFloat() / displayRange) * (height - 2 * padding)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    drawPath(path, lineColor, style = Stroke(width = 6f))
                    drawLine(
                        axisColor,
                        Offset(padding, height - padding),
                        Offset(width - padding, height - padding),
                        strokeWidth = 2f
                    )
                    drawLine(
                        axisColor,
                        Offset(padding, padding),
                        Offset(padding, height - padding),
                        strokeWidth = 2f
                    )
                }
            }

            ChartRangeSelector(
                selectedRange = selectedRange,
                onRangeSelected = { range ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    selectedRange = range
                }
            )
        }
    }
}

@Composable
private fun AveragesChart(solves: List<SolveTime>) {
    var selectedRange by remember { mutableStateOf("All") }
    val haptic = LocalHapticFeedback.current

    // calculateRollingAverages computes a trimmed-mean window for every solve; keep it off the
    // main thread so this always-visible chart never blocks composition.
    val rollingAverages by produceState<RollingAveragesData?>(initialValue = null, solves) {
        value = withContext(Dispatchers.Default) { calculateRollingAverages(solves) }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "Progress Chart")

            val data = rollingAverages
            if (data == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val ao5List = data.ao5List
            val ao12List = data.ao12List

            // Filter data based on selected range
            val displayAo5List = when (selectedRange) {
                "Last 50" -> ao5List.takeLast(50)
                "Last 100" -> ao5List.takeLast(100)
                else -> ao5List
            }
            val displayAo12List = when (selectedRange) {
                "Last 50" -> ao12List.takeLast(50)
                "Last 100" -> ao12List.takeLast(100)
                else -> ao12List
            }

        val ao5Color = MaterialTheme.colorScheme.primary
        val ao12Color = MaterialTheme.colorScheme.tertiary
        val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

        val validAo5 = displayAo5List.filterNotNull()
        val validAo12 = displayAo12List.filterNotNull()
        val allValues = validAo5 + validAo12

        if (allValues.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Complete 5 solves to see progress chart",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onSurfaceVariant
                )
            }
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                val width = size.width
                val height = size.height
                val padding = 40f
                
                val minValue = allValues.minOrNull() ?: 0L
                val maxValue = allValues.maxOrNull() ?: 1L
                val range = (maxValue - minValue).coerceAtLeast(1L)
            
            // Draw Ao12 line
            if (validAo12.isNotEmpty()) {
                val ao12Path = Path()
                var firstPoint = true
                displayAo12List.forEachIndexed { index, value ->
                    if (value != null) {
                        val x = padding + (index.toFloat() / (displayAo12List.size - 1).coerceAtLeast(1)) * (width - 2 * padding)
                        val y = height - padding - ((value - minValue).toFloat() / range) * (height - 2 * padding)
                        
                        if (firstPoint) {
                            ao12Path.moveTo(x, y)
                            firstPoint = false
                        } else {
                            ao12Path.lineTo(x, y)
                        }
                    }
                }
                drawPath(ao12Path, ao12Color, style = Stroke(width = 6f))
            }
            
            // Draw Ao5 line
            if (validAo5.isNotEmpty()) {
                val ao5Path = Path()
                var firstPoint = true
                displayAo5List.forEachIndexed { index, value ->
                    if (value != null) {
                        val x = padding + (index.toFloat() / (displayAo5List.size - 1).coerceAtLeast(1)) * (width - 2 * padding)
                        val y = height - padding - ((value - minValue).toFloat() / range) * (height - 2 * padding)
                        
                        if (firstPoint) {
                            ao5Path.moveTo(x, y)
                            firstPoint = false
                        } else {
                            ao5Path.lineTo(x, y)
                        }
                    }
                }
                drawPath(ao5Path, ao5Color, style = Stroke(width = 6f))
            }
            
            // Draw axes
            drawLine(
                onSurfaceVariant,
                Offset(padding, height - padding),
                Offset(width - padding, height - padding),
                strokeWidth = 2f
            )
            drawLine(
                onSurfaceVariant,
                Offset(padding, padding),
                Offset(padding, height - padding),
                strokeWidth = 2f
            )
            }
        }
        
            // Range selector
            ChartRangeSelector(
                selectedRange = selectedRange,
                onRangeSelected = { range ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    selectedRange = range
                }
            )
        
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(12.dp),
                            color = ao5Color,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {}
                        Text(
                            text = "Ao5",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(12.dp),
                            color = ao12Color,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {}
                        Text(
                            text = "Ao12",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRangeSelector(
    selectedRange: String,
    onRangeSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChartRangeOptions.forEach { range ->
            FilterChip(
                selected = selectedRange == range,
                onClick = { onRangeSelected(range) },
                shape = RoundedCornerShape(16.dp),
                label = {
                    Text(
                        text = range,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
            if (range != ChartRangeOptions.last()) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

private fun rangeStartIndex(solveCount: Int, selectedRange: String): Int {
    val visibleCount = when (selectedRange) {
        "Last 50" -> 50
        "Last 100" -> 100
        else -> solveCount
    }
    return (solveCount - visibleCount).coerceAtLeast(0)
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = contentColorFor(containerColor)
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(StatCardContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PenaltyCard(
    label: String,
    percentage: Int,
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color,
    contentColor: Color = contentColorFor(containerColor)
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(StatCardContentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "$percentage%",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "$count ${if (count == 1) "solve" else "solves"}",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}



private val StatCardContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val ChartRangeOptions = listOf("Last 50", "Last 100", "All")

private fun formatTime(millis: Long): String {
    return TimeFormatter.formatTime(millis)
}

private val timestampFormat by lazy {
    SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
}

private fun formatTimestamp(timestamp: Long): String {
    return timestampFormat.format(Date(timestamp))
}

// Session calculation: group solves with max 1 hour gap between consecutive solves
private data class AutoCalculatedSession(
    val solves: List<SolveTime>,
    val startTime: Long,
    val endTime: Long
)

private fun calculateSessions(solves: List<SolveTime>): List<AutoCalculatedSession> {
    if (solves.isEmpty()) return emptyList()
    
    val sessions = mutableListOf<AutoCalculatedSession>()
    val oneHourInMillis = 60 * 60 * 1000L
    
    var currentSession = mutableListOf<SolveTime>()
    var sessionStart = solves.first().timestamp
    
    for (i in solves.indices) {
        val solve = solves[i]
        
        if (currentSession.isEmpty()) {
            currentSession.add(solve)
            sessionStart = solve.timestamp
        } else {
            val timeSinceLastSolve = solve.timestamp - currentSession.last().timestamp
            
            if (timeSinceLastSolve > oneHourInMillis) {
                // Start new session
                sessions.add(AutoCalculatedSession(
                    solves = currentSession.toList(),
                    startTime = sessionStart,
                    endTime = currentSession.last().timestamp
                ))
                currentSession = mutableListOf(solve)
                sessionStart = solve.timestamp
            } else {
                currentSession.add(solve)
            }
        }
    }
    
    // Add the last session
    if (currentSession.isNotEmpty()) {
        sessions.add(AutoCalculatedSession(
            solves = currentSession.toList(),
            startTime = sessionStart,
            endTime = currentSession.last().timestamp
        ))
    }
    
    return sessions
}

private data class SessionStats(
    val bestSessionTime: Long,
    val worstSessionTime: Long,
    val sessionAverage: Long,
    val meanSolveTime: Long,
    val standardDeviation: Double,
    val avgTimeCubingInSession: Long
)

private fun calculateSessionStats(solves: List<SolveTime>): SessionStats? {
    val sessions = calculateSessions(solves)
    if (sessions.isEmpty()) return null
    
    val sessionAverages = sessions.map { session ->
        val validSolves = session.solves.filter { it.penalty != Penalty.DNF }
        if (validSolves.isEmpty()) Long.MAX_VALUE
        else validSolves.map { it.displayTime }.average().toLong()
    }.filter { it != Long.MAX_VALUE }
    
    if (sessionAverages.isEmpty()) return null
    
    val allValidSolves = solves.filter { it.penalty != Penalty.DNF }
    
    // Calculate average time cubing per session
    val avgTimeCubingInSession = if (sessions.isNotEmpty()) {
        val sessionDurations = sessions.map { session ->
            session.endTime - session.startTime
        }
        sessionDurations.average().toLong()
    } else 0L
    
    return SessionStats(
        bestSessionTime = sessionAverages.minOrNull() ?: 0L,
        worstSessionTime = sessionAverages.maxOrNull() ?: 0L,
        sessionAverage = sessionAverages.average().toLong(),
        meanSolveTime = AverageCalculator.mean(allValidSolves),
        standardDeviation = AverageCalculator.standardDeviation(allValidSolves),
        avgTimeCubingInSession = avgTimeCubingInSession
    )
}



private data class PersonalBestsData(
    val singlePBs: List<Pair<Int, Long>>,
    val ao5PBs: List<Pair<Int, Long>>,
    val ao12PBs: List<Pair<Int, Long>>
)

private fun calculatePersonalBests(solves: List<SolveTime>): PersonalBestsData {
    val singlePBs = mutableListOf<Pair<Int, Long>>()
    val ao5PBs = mutableListOf<Pair<Int, Long>>()
    val ao12PBs = mutableListOf<Pair<Int, Long>>()
    var bestSingle = Long.MAX_VALUE
    var bestAo5 = Long.MAX_VALUE
    var bestAo12 = Long.MAX_VALUE

    for (i in solves.indices) {
        val solve = solves[i]
        if (solve.penalty != Penalty.DNF && solve.displayTime < bestSingle) {
            bestSingle = solve.displayTime
            singlePBs.add(i to bestSingle)
        }
        if (i >= 4) {
            AverageCalculator.averageWindow(solves.subList(i - 4, i + 1))?.let { avg ->
                if (avg < bestAo5) {
                    bestAo5 = avg
                    ao5PBs.add(i to bestAo5)
                }
            }
        }
        if (i >= 11) {
            AverageCalculator.averageWindow(solves.subList(i - 11, i + 1))?.let { avg ->
                if (avg < bestAo12) {
                    bestAo12 = avg
                    ao12PBs.add(i to bestAo12)
                }
            }
        }
    }
    return PersonalBestsData(singlePBs, ao5PBs, ao12PBs)
}

private data class RollingAveragesData(
    val ao5List: List<Long?>,
    val ao12List: List<Long?>
)

private fun calculateRollingAverages(solves: List<SolveTime>): RollingAveragesData {
    val ao5List = MutableList<Long?>(solves.size) { null }
    val ao12List = MutableList<Long?>(solves.size) { null }
    for (i in solves.indices) {
        if (i >= 4) {
            ao5List[i] = AverageCalculator.averageWindow(solves.subList(i - 4, i + 1))
        }
        if (i >= 11) {
            ao12List[i] = AverageCalculator.averageWindow(solves.subList(i - 11, i + 1))
        }
    }
    return RollingAveragesData(ao5List, ao12List)
}

