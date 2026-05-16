package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.Penalty
import com.maciekhetman.cubetimer.SolveTime
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.ui.components.ActivityTracker
import com.maciekhetman.cubetimer.ui.components.SectionDivider
import com.maciekhetman.cubetimer.ui.components.SectionHeader
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: TimerViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val solves by viewModel.solves.collectAsState()
    val appTimeMillis by viewModel.appTimeMillis.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val layoutDirection = LocalLayoutDirection.current
    val haptic = LocalHapticFeedback.current

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
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (solves.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No solves yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        } else {
            val collapsedFraction = scrollBehavior.state.collapsedFraction
            val topPadding = paddingValues.calculateTopPadding() * (1f - collapsedFraction)
            val startPadding = paddingValues.calculateStartPadding(layoutDirection)
            val endPadding = paddingValues.calculateEndPadding(layoutDirection)
            val bottomPadding = paddingValues.calculateBottomPadding()

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = startPadding,
                        top = topPadding,
                        end = endPadding,
                        bottom = bottomPadding
                    ),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    StatsHeader(solves = solves, appTimeMillis = appTimeMillis)
                }
                
                item {
                    SectionDivider()
                }
                
                item {
                    SessionStatsSection(solves = solves)
                }
                
                item {
                    SectionDivider()
                }
                
                item {
                    ChartsSection(solves = solves)
                }
                
                item {
                    SectionDivider()
                }
                
                item {
                    AveragesSection(solves = solves)
                }
                
                item {
                    SectionDivider()
                }
                
                item {
                    LargeAveragesSection(solves = solves)
                }
                
                item {
                    SectionDivider()
                }
                
                item {
                    ActivityTracker(solves = solves)
                }
                
                item {
                    SectionDivider()
                }
                
                item {
                    PenaltyStatsSection(solves = solves)
                }
                
                item {
                    SectionDivider()
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "Solve History",
                            modifier = Modifier.weight(1f)
                        )
                        if (solves.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showClearDialog = true
                                }
                            ) {
                                Text("Clear All")
                            }
                        }
                    }
                }
                
                val recentSolves = if (solves.size > 200) solves.takeLast(200) else solves
                itemsIndexed(
                    items = recentSolves.reversed(),
                    key = { _, solve -> solve.timestamp }
                ) { index, solve ->
                    val deleteSolve: () -> Unit = {
                        viewModel.deleteSolve(solve)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Solve deleted",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.addSolve(solve)
                            }
                        }
                    }
                    val setPenalty: (Penalty) -> Unit = setPenalty@{ penalty ->
                        if (solve.penalty == penalty) return@setPenalty
                        val previousPenalty = solve.penalty
                        viewModel.updateSolvePenalty(solve, penalty)
                        val penaltyLabel = when (penalty) {
                            Penalty.DNF -> "DNF"
                            Penalty.PLUS_TWO -> "+2"
                            Penalty.NONE -> "None"
                        }
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Penalty set to $penaltyLabel",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.updateSolvePenalty(solve, previousPenalty)
                            }
                        }
                    }

                    SolveCard(
                        solve = solve,
                        solveNumber = solves.size - index,
                        onDelete = deleteSolve,
                        onSetPenalty = setPenalty,
                        onHaptic = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Solves?") },
            text = { Text("This will delete all ${solves.size} solve(s) from your history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.clearAllSolves()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showClearDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ChartsSection(solves: List<SolveTime>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(title = "Charts")
        }

        PersonalBestsChart(solves = solves)
        SolveTimesChart(solves = solves)
        AveragesChart(solves = solves)
    }
}

@Composable
private fun StatsHeader(solves: List<SolveTime>, appTimeMillis: Long) {
    val validSolves = solves.filter { it.penalty != Penalty.DNF }
    val avgTime = if (validSolves.isNotEmpty()) {
        validSolves.map { it.displayTime }.average()
    } else 0.0
    val bestTime = validSolves.minOfOrNull { it.displayTime } ?: 0
    val worstTime = validSolves.maxOfOrNull { it.displayTime } ?: 0
    val totalSolvingTime = solves.sumOf { it.timeInMillis }
    val meanTime = calculateMean(validSolves)
    val standardDeviation = calculateStandardDeviation(validSolves)
    
    // Calculate sessions and average solves per day
    val sessions = calculateSessions(solves)
    val totalSessions = sessions.size
    
    val avgSolvesPerDay = if (solves.isNotEmpty()) {
        val firstSolveDate = solves.first().timestamp
        val lastSolveDate = solves.last().timestamp
        val daysDifference = ((lastSolveDate - firstSolveDate) / (1000 * 60 * 60 * 24)).toInt() + 1
        if (daysDifference > 0) {
            (solves.size / daysDifference).toString()
        } else {
            solves.size.toString()
        }
    } else "0"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(title = "Overview")
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
        ) {
            StatCard(
                label = "Total",
                value = solves.size.toString(),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
            StatCard(
                label = "Best",
                value = formatTime(bestTime),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
        ) {
            StatCard(
                label = "Worst",
                value = formatTime(worstTime),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
            StatCard(
                label = "Average",
                value = formatTime(avgTime.toLong()),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
        ) {
            StatCard(
                label = "Mean Time",
                value = formatTime(meanTime),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
            StatCard(
                label = "Std. Deviation",
                value = formatTime(standardDeviation.toLong()),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
        ) {
            StatCard(
                label = "Sessions",
                value = totalSessions.toString(),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            )
            StatCard(
                label = "Avg/Day",
                value = avgSolvesPerDay,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
        ) {
            StatCard(
                label = "Time Cubing",
                value = formatDuration(appTimeMillis),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
            StatCard(
                label = "Time Solving",
                value = formatDuration(totalSolvingTime),
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        }
    }
}

@Composable
private fun AveragesSection(solves: List<SolveTime>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionHeader(title = "Averages")
        
        val ao5Current = calculateAverageOfN(solves, 5)
        val ao12Current = calculateAverageOfN(solves, 12)
        val bestAo5 = findBestAverageOfN(solves, 5)
        val bestAo12 = findBestAverageOfN(solves, 12)
        
        // Ao5 Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Average of 5",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
                ) {
                    AverageCard(
                        label = "Current",
                        value = if (ao5Current != null) formatTime(ao5Current) else "N/A",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    AverageCard(
                        label = "Personal Best",
                        value = if (bestAo5 != null) formatTime(bestAo5) else "N/A",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Ao12 Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Average of 12",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
                ) {
                    AverageCard(
                        label = "Current",
                        value = if (ao12Current != null) formatTime(ao12Current) else "N/A",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    AverageCard(
                        label = "Personal Best",
                        value = if (bestAo12 != null) formatTime(bestAo12) else "N/A",
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeAveragesSection(solves: List<SolveTime>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(title = "Session Averages")
        
        val averages = listOf(
            50 to "Ao50",
            100 to "Ao100",
            200 to "Ao200",
            500 to "Ao500",
            1000 to "Ao1000",
            2000 to "Ao2000"
        )
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            averages.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (count, label) ->
                        val current = calculateAverageOfN(solves, count)
                        val best = findBestAverageOfN(solves, count)
                        
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 88.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (current != null) formatTime(current) else "N/A",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                )
                                // Always reserve space for PB line
                                if (best != null && best != current) {
                                    Text(
                                        text = "PB: ${formatTime(best)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    // Empty spacer to maintain consistent height
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                    
                    // Add spacer if odd number of items in row
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionStatsSection(solves: List<SolveTime>) {
    val sessionStats = calculateSessionStats(solves)
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(title = "Session Statistics")
        
        if (sessionStats == null) {
            Text(
                text = "Not enough data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            Text(
                text = "Sessions are groups of solves with no more than 1 hour gap between consecutive solves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
            ) {
                StatCard(
                    label = "Session Best",
                    value = formatTime(sessionStats.bestSessionTime),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
                StatCard(
                    label = "Session Worst",
                    value = formatTime(sessionStats.worstSessionTime),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
            ) {
                StatCard(
                    label = "Session Avg",
                    value = formatTime(sessionStats.sessionAverage),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
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
                horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
            ) {
                StatCard(
                    label = "Std. Deviation",
                    value = formatTime(sessionStats.standardDeviation.toLong()),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                StatCard(
                    label = "Time Cubing",
                    value = formatDuration(sessionStats.avgTimeCubingInSession),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            }
        }
    }
}

@Composable
private fun PenaltyStatsSection(solves: List<SolveTime>) {
    val dnfCount = solves.count { it.penalty == Penalty.DNF }
    val plusTwoCount = solves.count { it.penalty == Penalty.PLUS_TWO }
    val totalCount = solves.size.toFloat()
    
    val dnfPercent = if (totalCount > 0) (dnfCount / totalCount * 100).toInt() else 0
    val plusTwoPercent = if (totalCount > 0) (plusTwoCount / totalCount * 100).toInt() else 0
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader(title = "Penalties")
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(StatCardSpacing)
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
    val validSolves = solves.filter { it.penalty != Penalty.DNF }
    
    if (validSolves.isEmpty()) {
        return
    }
    
    // Track PB progression
    val singlePBs = mutableListOf<Pair<Int, Long>>() // solve number to PB time
    val ao5PBs = mutableListOf<Pair<Int, Long>>()
    val ao12PBs = mutableListOf<Pair<Int, Long>>()
    
    var currentBestSingle = Long.MAX_VALUE
    var currentBestAo5 = Long.MAX_VALUE
    var currentBestAo12 = Long.MAX_VALUE
    
    for (i in solves.indices) {
        val subList = solves.take(i + 1)
        val validSubList = subList.filter { it.penalty != Penalty.DNF }
        
        // Check single PB
        if (validSubList.isNotEmpty()) {
            val bestInSubList = validSubList.minOf { it.displayTime }
            if (bestInSubList < currentBestSingle) {
                currentBestSingle = bestInSubList
                singlePBs.add(Pair(i, currentBestSingle))
            }
        }
        
        // Check Ao5 PB
        val ao5 = calculateAverageOfN(subList, 5)
        if (ao5 != null && ao5 < currentBestAo5) {
            currentBestAo5 = ao5
            ao5PBs.add(Pair(i, currentBestAo5))
        }
        
        // Check Ao12 PB
        val ao12 = calculateAverageOfN(subList, 12)
        if (ao12 != null && ao12 < currentBestAo12) {
            currentBestAo12 = ao12
            ao12PBs.add(Pair(i, currentBestAo12))
        }
    }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "Personal Best Progress")
            
            val singleColor = Color(0xFF4CAF50) // Green
            val ao5Color = Color(0xFF2196F3) // Blue
            val ao12Color = Color(0xFFFF9800) // Orange
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
                        color = onSurfaceVariant.copy(alpha = 0.6f)
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
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large
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
    val visibleSolves = solves
        .mapIndexed { index, solve -> index to solve }
        .filter { (index, solve) -> index >= rangeStartIndex && solve.penalty != Penalty.DNF }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                val smoothed = visibleSolves.mapIndexed { visibleIndex, (solveIndex, solve) ->
                    val windowStart = (visibleIndex - 2).coerceAtLeast(0)
                    val windowEnd = (visibleIndex + 2).coerceAtMost(visibleSolves.lastIndex)
                    val average = visibleSolves
                        .subList(windowStart, windowEnd + 1)
                        .map { it.second.displayTime }
                        .average()
                        .toLong()
                    solveIndex to average
                }
                val values = smoothed.map { it.second }
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
    
    val ao5List = mutableListOf<Long?>()
    val ao12List = mutableListOf<Long?>()
    
    for (i in solves.indices) {
        val subList = solves.take(i + 1)
        ao5List.add(calculateAverageOfN(subList, 5))
        ao12List.add(calculateAverageOfN(subList, 12))
    }
    
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
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "Progress Chart")
        
        val ao5Color = Color(0xFF2196F3) // Blue
        val ao12Color = Color(0xFFFF9800) // Orange
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
                    color = onSurfaceVariant.copy(alpha = 0.6f)
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
                val range = maxValue - minValue
                
                if (range == 0L) return@Canvas
            
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large
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
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                color = contentColor.copy(alpha = 0.8f),
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
private fun AverageCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
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
                color = contentColor.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val fontSize = when {
                    maxWidth < 120.dp -> 18.sp
                    maxWidth < 150.dp -> 22.sp
                    else -> 28.sp
                }
                Text(
                    text = value,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false
                )
            }
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
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                color = contentColor.copy(alpha = 0.8f),
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
                color = contentColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SolveCard(
    solve: SolveTime,
    solveNumber: Int,
    onDelete: () -> Unit,
    onSetPenalty: (Penalty) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Solve #$solveNumber",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                    Box {
                        IconButton(onClick = {
                            onHaptic()
                            menuExpanded = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Solve options",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            tonalElevation = 6.dp,
                            shadowElevation = 8.dp
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    onHaptic()
                                    menuExpanded = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add DNF") },
                                onClick = {
                                    onHaptic()
                                    menuExpanded = false
                                    onSetPenalty(Penalty.DNF)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add +2") },
                                onClick = {
                                    onHaptic()
                                    menuExpanded = false
                                    onSetPenalty(Penalty.PLUS_TWO)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = formatTime(solve.displayTime),
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
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = when (solve.penalty) {
                                    Penalty.DNF -> "DNF"
                                    Penalty.PLUS_TWO -> "+2"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = when (solve.penalty) {
                                    Penalty.DNF -> MaterialTheme.colorScheme.onErrorContainer
                                    Penalty.PLUS_TWO -> MaterialTheme.colorScheme.onTertiaryContainer
                                    else -> Color.Unspecified
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatTimestamp(solve.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                if (solve.scramble.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = solve.scramble,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

private val StatCardContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
private val StatCardSpacing = 10.dp
private val ChartRangeOptions = listOf("Last 50", "Last 100", "All")

private fun formatTime(millis: Long): String {
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

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return when {
        hours > 0 -> String.format("%dh %dm", hours, minutes)
        minutes > 0 -> String.format("%dm %ds", minutes, seconds)
        else -> String.format("%ds", seconds)
    }
}

private fun calculateAverageOfN(solves: List<SolveTime>, n: Int): Long? {
    if (solves.size < n) return null
    val lastN = solves.takeLast(n)
    val validSolves = lastN.filter { it.penalty != Penalty.DNF }
    
    // Need at least 60% valid solves for official average
    if (validSolves.size < (n * 0.6).toInt()) return null
    
    return validSolves.map { it.displayTime }.average().toLong()
}

private fun findBestAverageOfN(solves: List<SolveTime>, n: Int): Long? {
    if (solves.size < n) return null
    
    var bestAverage: Long? = null
    
    for (i in 0..(solves.size - n)) {
        val subList = solves.subList(i, i + n)
        val validSolves = subList.filter { it.penalty != Penalty.DNF }
        
        if (validSolves.size >= (n * 0.6).toInt()) {
            val average = validSolves.map { it.displayTime }.average().toLong()
            if (bestAverage == null || average < bestAverage) {
                bestAverage = average
            }
        }
    }
    
    return bestAverage
}

// Session calculation: group solves with max 1 hour gap between consecutive solves
private data class Session(
    val solves: List<SolveTime>,
    val startTime: Long,
    val endTime: Long
)

private fun calculateSessions(solves: List<SolveTime>): List<Session> {
    if (solves.isEmpty()) return emptyList()
    
    val sessions = mutableListOf<Session>()
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
                sessions.add(Session(
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
        sessions.add(Session(
            solves = currentSession.toList(),
            startTime = sessionStart,
            endTime = currentSession.last().timestamp
        ))
    }
    
    return sessions
}

private fun calculateMean(solves: List<SolveTime>): Long {
    val validSolves = solves.filter { it.penalty != Penalty.DNF }
    if (validSolves.isEmpty()) return 0L
    return validSolves.map { it.displayTime }.average().toLong()
}

private fun calculateStandardDeviation(solves: List<SolveTime>): Double {
    val validSolves = solves.filter { it.penalty != Penalty.DNF }
    if (validSolves.size < 2) return 0.0
    
    val times = validSolves.map { it.displayTime.toDouble() }
    val mean = times.average()
    val variance = times.map { (it - mean).pow(2) }.average()
    
    return sqrt(variance)
}

private data class SessionStats(
    val bestSessionTime: Long,
    val worstSessionTime: Long,
    val sessionAverage: Long,
    val meanSolveTime: Long,
    val standardDeviation: Double,
    val totalSessions: Int,
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
        meanSolveTime = calculateMean(allValidSolves),
        standardDeviation = calculateStandardDeviation(allValidSolves),
        totalSessions = sessions.size,
        avgTimeCubingInSession = avgTimeCubingInSession
    )
}



