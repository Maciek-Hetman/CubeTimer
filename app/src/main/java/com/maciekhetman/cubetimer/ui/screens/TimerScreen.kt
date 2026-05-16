package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maciekhetman.cubetimer.Mode
import com.maciekhetman.cubetimer.Penalty
import com.maciekhetman.cubetimer.RecordCelebration
import com.maciekhetman.cubetimer.RecordType
import com.maciekhetman.cubetimer.RunningTimerDisplay
import com.maciekhetman.cubetimer.SolveTime
import com.maciekhetman.cubetimer.TimerState
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.TimerAverageOptions
import com.maciekhetman.cubetimer.ui.components.TopBar
import kotlinx.coroutines.delay

@Composable
fun TimerScreen(
    viewModel: TimerViewModel,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    modifier: Modifier = Modifier
) {
    val timerState by viewModel.timerState.collectAsState()
    val solves by viewModel.solves.collectAsState()
    val scramble by viewModel.currentScramble.collectAsState()
    val recordCelebration by viewModel.recordCelebration.collectAsState()
    val showScrambleRefreshButton by viewModel.showScrambleRefreshButton.collectAsState()
    val scrambleScalePercent by viewModel.scrambleScalePercent.collectAsState()
    val timerAverages by viewModel.timerAverages.collectAsState()
    val runningTimerDisplay by viewModel.runningTimerDisplay.collectAsState()
    val hideScrambleDuringSolve by viewModel.hideScrambleDuringSolve.collectAsState()
    val hideAveragesDuringSolve by viewModel.hideAveragesDuringSolve.collectAsState()
    val hideLastResultsDuringSolve by viewModel.hideLastResultsDuringSolve.collectAsState()
    val hideLastResultsOnTimer by viewModel.hideLastResultsOnTimer.collectAsState()
    val focusMode by viewModel.focusMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    val isSolving = timerState is TimerState.Running
    val focusModeActive = focusMode && isSolving
    val showTopBar = !focusModeActive
    val showScramble = !isSolving || (!hideScrambleDuringSolve && !focusModeActive)
    val showAverages = solves.isNotEmpty() && (!isSolving || (!hideAveragesDuringSolve && !focusModeActive))
    val showLastResults = solves.isNotEmpty() &&
        !hideLastResultsOnTimer &&
        (!isSolving || (!hideLastResultsDuringSolve && !focusModeActive))
    val showBottomContent = showAverages || showLastResults
    
    // Trigger haptic feedback only once when timer starts
    LaunchedEffect(timerState is TimerState.Running) {
        if (timerState is TimerState.Running) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    
    // Trigger enhanced haptic feedback when record is broken
    LaunchedEffect(recordCelebration) {
        if (recordCelebration != null) {
            // First strong pulse
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(100)
            // Second pulse
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(100)
            // Third pulse
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showTopBar) {
            TopBar(
                title = "Timer",
                currentMode = currentMode,
                onModeSelected = onModeSelected
            )
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (showScramble) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ScrambleDisplay(
                        scramble = scramble,
                        onRefresh = { viewModel.generateNewScramble() },
                        showRefreshButton = showScrambleRefreshButton,
                        scale = scrambleScalePercent / 100f
                    )
                }
            }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (showScramble) 100.dp else 12.dp,
                    bottom = if (showBottomContent) 100.dp else 12.dp
                )
                .then(
                    if (timerState !is TimerState.Finished && recordCelebration == null) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    viewModel.onPressStart()
                                    tryAwaitRelease()
                                    val isRunning = viewModel.timerState.value is TimerState.Running
                                    viewModel.onPressRelease()
                                    if (isRunning) {
                                        // Timer stopped
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            TimerContent(
                timerState = timerState,
                viewModel = viewModel,
                runningTimerDisplay = runningTimerDisplay,
                focusModeActive = focusModeActive
            )
        }
        
        if (showBottomContent) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showAverages) {
                    AveragesDisplay(
                        solves = solves,
                        enabledAverages = timerAverages
                    )
                }
                if (showLastResults) {
                    RecentSolvesDisplay(solves = solves)
                }
            }
        }        
            // Record celebration overlay
            RecordCelebrationOverlay(
                celebration = recordCelebration,
                onDismiss = { viewModel.dismissRecordCelebration() }
            )
        }
    }
}

@Composable
private fun TimerContent(
    timerState: TimerState,
    viewModel: TimerViewModel,
    runningTimerDisplay: RunningTimerDisplay,
    focusModeActive: Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val showTimerDisplay = timerState !is TimerState.Running ||
            (!focusModeActive && runningTimerDisplay != RunningTimerDisplay.HIDDEN)
        if (showTimerDisplay) {
            TimerDisplay(
                time = when (timerState) {
                    is TimerState.Running -> timerState.elapsedTime
                    is TimerState.Finished -> timerState.time
                    else -> 0
                },
                color = when (timerState) {
                    is TimerState.Holding -> MaterialTheme.colorScheme.error
                    is TimerState.Ready -> MaterialTheme.colorScheme.primary
                    is TimerState.Running -> MaterialTheme.colorScheme.primary
                    is TimerState.Finished -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onBackground
                },
                showDecimals = timerState !is TimerState.Running ||
                    runningTimerDisplay == RunningTimerDisplay.FULL
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        when (timerState) {
            is TimerState.Idle -> {
                Text(
                    text = "Tap and hold to start",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            is TimerState.Holding -> {
                val color = if (timerState.progress < 1f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
                LinearProgressIndicator(
                    progress = { timerState.progress },
                    modifier = Modifier
                        .width(200.dp)
                        .height(4.dp),
                    color = color,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Hold...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = color
                )
            }
            is TimerState.Ready -> {
                Text(
                    text = "Release to start!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
            }
            is TimerState.Running -> {
                Text(
                    text = "Tap to stop",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }
            is TimerState.Finished -> {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.saveSolveWithPenalty(Penalty.NONE)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            text = "Save Time",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.saveSolveWithPenalty(Penalty.PLUS_TWO)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                text = "+2",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.saveSolveWithPenalty(Penalty.DNF)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(
                                text = "DNF",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.discardSolve()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = "Discard",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerDisplay(
    time: Long,
    color: Color,
    showDecimals: Boolean,
    modifier: Modifier = Modifier
) {
    val totalSeconds = time / 1000
    val milliseconds = (time % 1000) / 10
    
    val (mainText, millisecondsText) = if (time == 0L) {
        "0" to ".00"
    } else if (totalSeconds >= 60) {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        String.format("%d:%02d", minutes, seconds) to String.format(".%02d", milliseconds)
    } else {
        String.format("%d", totalSeconds) to String.format(".%02d", milliseconds)
    }
    
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.padding(bottom = 8.dp)
    ) {
        Text(
            text = mainText,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        if (showDecimals) {
            Text(
                text = millisecondsText,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun AveragesDisplay(
    solves: List<SolveTime>,
    enabledAverages: Set<Int>,
    modifier: Modifier = Modifier
) {
    val averages = TimerAverageOptions
        .filter { it in enabledAverages }
        .mapNotNull { count ->
            calculateTimerAverage(solves, count)?.let { average ->
                count to average
            }
        }

    if (averages.isEmpty()) {
        return
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            averages.chunked(3).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    row.forEach { (count, average) ->
                        AverageStat(label = "Ao$count", time = average)
                    }
                }
            }
        }
    }
}

private fun calculateTimerAverage(solves: List<SolveTime>, count: Int): Long? {
    if (solves.size < count) return null

    val lastSolves = solves.takeLast(count)
    val validSolves = lastSolves.filter { it.penalty != Penalty.DNF }
    val minimumValidSolves = when (count) {
        5 -> 3
        12 -> 10
        else -> (count * 0.6f).toInt()
    }

    if (validSolves.size < minimumValidSolves) return null

    return validSolves.map { it.displayTime }.average().toLong()
}

@Composable
private fun AverageStat(
    label: String,
    time: Long
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = formatDisplayTime(time),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun RecentSolvesDisplay(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    val recentSolves = solves.takeLast(5).reversed()
    
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth
        val itemCount = recentSolves.size
        val spacing = 12.dp * (itemCount - 1)
        val horizontalPadding = 16.dp // Total padding (8dp per item)
        val availableForItems = availableWidth - spacing - horizontalPadding
        val itemWidth = availableForItems / itemCount
        
        // Calculate dynamic font size based on available width
        val dynamicFontSize = when {
            itemWidth < 50.dp -> 9.sp
            itemWidth < 60.dp -> 10.sp
            else -> 11.sp
        }
        
        val dynamicHorizontalPadding = when {
            itemWidth < 50.dp -> 4.dp
            itemWidth < 60.dp -> 5.dp
            else -> 6.dp
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            recentSolves.forEach { solve ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Text(
                        text = formatDisplayTime(solve.displayTime),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = dynamicFontSize,
                        fontFamily = FontFamily.Monospace,
                        color = when (solve.penalty) {
                            Penalty.DNF -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            Penalty.PLUS_TWO -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                            Penalty.NONE -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        },
                        modifier = Modifier.padding(horizontal = dynamicHorizontalPadding, vertical = 2.dp)
                    )
                }
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrambleDisplay(
    scramble: String,
    onRefresh: () -> Unit,
    showRefreshButton: Boolean,
    scale: Float,
    modifier: Modifier = Modifier
) {
    var showFullScramble by remember { mutableStateOf(false) }
    var isTruncated by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val maxLines = 4
    val safeScale = scale.coerceIn(0.8f, 1.4f)
    val contentPadding = 16.dp * safeScale
    val spacerWidth = 12.dp * safeScale
    val textEndPadding = 8.dp * safeScale
    val buttonSize = 40.dp * safeScale
    val iconSize = 20.dp * safeScale
    val baseTextStyle = MaterialTheme.typography.bodyLarge
    val scrambleTextStyle = baseTextStyle.copy(
        fontSize = baseTextStyle.fontSize * safeScale
    )
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(
                if (isTruncated) {
                    Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showFullScramble = true
                    }
                } else {
                    Modifier
                }
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = scramble,
                style = scrambleTextStyle,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textLayoutResult ->
                    isTruncated = textLayoutResult.hasVisualOverflow
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = textEndPadding)
            )
            if (showRefreshButton) {
                Spacer(modifier = Modifier.width(spacerWidth))
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRefresh()
                    },
                    modifier = Modifier.size(buttonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Generate new scramble",
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
    
    // Full scramble dialog
    if (showFullScramble) {
        AlertDialog(
            onDismissRequest = { showFullScramble = false },
            title = {
                Text(
                    text = "Full Scramble",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = scramble,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showFullScramble = false
                }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun RecordCelebrationOverlay(
    celebration: RecordCelebration?,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    AnimatedVisibility(
        visible = celebration != null,
        enter = fadeIn(animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + 
                scaleIn(
                    initialScale = 0.7f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
        exit = fadeOut(animationSpec = tween(300)) + 
               scaleOut(
                   targetScale = 0.95f,
                   animationSpec = tween(300)
               )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pointerInput(Unit) {
                    detectTapGestures {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            celebration?.let {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .padding(16.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "New record",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Text(
                                text = when (it.type) {
                                    RecordType.BEST_SINGLE -> "Best Single"
                                    RecordType.BEST_AO5 -> "Best Average of 5"
                                    RecordType.BEST_AO12 -> "Best Average of 12"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = formatDisplayTime(it.time),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            softWrap = true,
                            maxLines = 2,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .fillMaxWidth()
                        )
                        Text(
                            text = "Tap to continue",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
