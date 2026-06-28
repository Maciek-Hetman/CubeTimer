package com.maciekhetman.cubetimer.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.maciekhetman.cubetimer.model.RecordCelebration
import com.maciekhetman.cubetimer.model.RecordType
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.TimerAverageOptions
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.ui.components.TopBar
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel
import kotlin.time.Duration.Companion.milliseconds
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
    val timerStartDelayMillis by viewModel.timerStartDelayMillis.collectAsState()
    val timerAverages by viewModel.timerAverages.collectAsState()
    val runningTimerDisplay by viewModel.runningTimerDisplay.collectAsState()
    val hideScrambleDuringSolve by viewModel.hideScrambleDuringSolve.collectAsState()
    val hideAveragesDuringSolve by viewModel.hideAveragesDuringSolve.collectAsState()
    val hideLastResultsDuringSolve by viewModel.hideLastResultsDuringSolve.collectAsState()
    val hideLastResultsOnTimer by viewModel.hideLastResultsOnTimer.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsState()
    val focusMode by viewModel.focusMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isSolving = timerState is TimerState.Running
    val focusModeActive = focusMode && isSolving
    val showTopBar = !focusModeActive
    val showScramble = !isSolving || (!hideScrambleDuringSolve && !focusModeActive)
    val showAverages = solves.isNotEmpty() && (!isSolving || (!hideAveragesDuringSolve && !focusModeActive))
    val showLastResults = solves.isNotEmpty() &&
        !hideLastResultsOnTimer &&
        (!isSolving || (!hideLastResultsDuringSolve && !focusModeActive))
    val showBottomContent = showAverages || showLastResults
    
    val latestTimerState by rememberUpdatedState(timerState)

    LaunchedEffect((timerState is TimerState.Holding), timerStartDelayMillis, hapticsEnabled) {
        if ((timerState is TimerState.Holding) && hapticsEnabled) {
            val holdDuration = timerStartDelayMillis.coerceAtLeast(200)
            val pulses = listOf(
                0.14f to 22,
                0.34f to 44,
                0.52f to 72,
                0.68f to 108,
                0.82f to 148,
                0.93f to 190
            )
            var previousDelay = 0L

            pulses.forEach { (fraction, amplitude) ->
                val targetDelay = (holdDuration * fraction).toLong()
                delay((targetDelay - previousDelay).milliseconds)
                if (latestTimerState !is TimerState.Holding) return@LaunchedEffect
                vibrateOneShot(context, durationMillis = 8L, amplitude = amplitude)
                previousDelay = targetDelay
            }
        }
    }

    // Trigger haptic feedback only once when timer starts
    LaunchedEffect(timerState is TimerState.Running, hapticsEnabled) {
        if (timerState is TimerState.Running && hapticsEnabled) {
            val usedVibrator = vibrateOneShot(context, durationMillis = 14L, amplitude = 255)
            if (!usedVibrator) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    
    // Trigger enhanced haptic feedback when record is broken
    LaunchedEffect(recordCelebration) {
        if (recordCelebration != null) {
            // First strong pulse
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(100.milliseconds)
            // Second pulse
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(100.milliseconds)
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
                    bottom = if (showBottomContent) 180.dp else 96.dp
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
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 88.dp, top = 8.dp),
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
                celebration = recordCelebration
            ) { viewModel.dismissRecordCelebration() }
        }
    }
}

private fun vibrateOneShot(
    context: Context,
    durationMillis: Long,
    amplitude: Int
): Boolean {
    val vibrator = context.defaultVibrator() ?: return false
    if (!vibrator.hasVibrator()) return false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                durationMillis,
                amplitude.coerceIn(1, 255)
            )
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMillis)
    }
    return true
}

private fun Context.defaultVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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
    var showDiscardDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val showTimerDisplay = (timerState !is TimerState.Running) ||
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
                                showDiscardDialog = true
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

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard solve?") },
            text = { Text("This solve will be removed without saving.") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDiscardDialog = false
                        viewModel.discardSolve()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDiscardDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TimerDisplay(
    time: Long,
    color: Color,
    showDecimals: Boolean,
    modifier: Modifier = Modifier
) {
    val (mainText, millisecondsText) = TimeFormatter.splitTimerTime(time)
    
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
        .asSequence()
        .filter { it in enabledAverages }
        .mapNotNull { count ->
            AverageCalculator.averageOfN(solves, count)?.let { average ->
                count to average
            }
        }
        .toList()

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
    val recentSolves = remember(solves) {
        solves.takeLast(5).reversed()
    }

    if (recentSolves.isEmpty()) return

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val itemCount = recentSolves.size
        val availableWidth = this.maxWidth
        
        val itemSpacing = 12.dp
        val totalSpacing = itemSpacing * (itemCount - 1)
        val estimatedItemWidth = (availableWidth - totalSpacing) / itemCount

        val dynamicFontSize = when {
            estimatedItemWidth < 50.dp -> 9.sp
            estimatedItemWidth < 60.dp -> 10.sp
            else -> 11.sp
        }

        val dynamicHorizontalPadding = when {
            estimatedItemWidth < 50.dp -> 4.dp
            estimatedItemWidth < 60.dp -> 5.dp
            else -> 6.dp
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(itemSpacing, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            recentSolves.forEach { solve ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
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
                        modifier = Modifier.padding(
                            horizontal = dynamicHorizontalPadding,
                            vertical = 2.dp
                        )
                    )
                }
            }
        }
    }
}

private fun formatDisplayTime(millis: Long): String {
    return TimeFormatter.formatTime(millis)
}

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
