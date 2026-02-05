package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import com.maciekhetman.cubetimer.SolveTime
import com.maciekhetman.cubetimer.TimerState
import com.maciekhetman.cubetimer.TimerViewModel
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenu
import com.maciekhetman.cubetimer.ui.components.ExpressiveDropdownMenuItem
import com.maciekhetman.cubetimer.ui.components.TopBar
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
    val cubes by viewModel.cubes.collectAsState()
    val activeCubeIdByMode by viewModel.activeCubeIdByMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    val activeCubeId = activeCubeIdByMode[currentMode]
    val cubesForMode = cubes.filter { it.type == currentMode }
    val activeCubeName = activeCubeId?.let { activeId ->
        cubesForMode.firstOrNull { it.id == activeId }?.displayName
    }
    var activeCubeMenuExpanded by remember { mutableStateOf(false) }
    var activeCubePillWidthPx by remember { mutableStateOf(0) }
    val activeCubePillWidthDp = with(LocalDensity.current) { activeCubePillWidthPx.toDp() }
    
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
        TopBar(
            title = "Timer",
            currentMode = currentMode,
            onModeSelected = onModeSelected
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
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
                if (!activeCubeName.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.large,
                                tonalElevation = 1.dp,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .defaultMinSize(minHeight = 36.dp)
                                    .onSizeChanged { activeCubePillWidthPx = it.width }
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        activeCubeMenuExpanded = true
                                    }
                            ) {
                                Text(
                                    text = activeCubeName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }

                            ExpressiveDropdownMenu(
                                expanded = activeCubeMenuExpanded,
                                onDismissRequest = { activeCubeMenuExpanded = false },
                                modifier = if (activeCubePillWidthPx > 0) {
                                    Modifier.width(activeCubePillWidthDp)
                                } else {
                                    Modifier
                                }
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
                    }
                }
            }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 100.dp,
                    bottom = if (solves.isNotEmpty()) 100.dp else 12.dp
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
                viewModel = viewModel
            )
        }
        
        if (solves.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AveragesDisplay(solves = solves)
                RecentSolvesDisplay(solves = solves)
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
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (timerState) {
            is TimerState.Idle -> {
                TimerDisplay(time = 0, color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(24.dp))
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
                TimerDisplay(time = 0, color = color)
                Spacer(modifier = Modifier.height(24.dp))
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
                TimerDisplay(time = 0, color = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Release to start!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
            }
            is TimerState.Running -> {
                TimerDisplay(time = timerState.elapsedTime, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Tap to stop",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }
            is TimerState.Finished -> {
                TimerDisplay(time = timerState.time, color = MaterialTheme.colorScheme.tertiary)
                Spacer(modifier = Modifier.height(40.dp))
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.saveSolveWithPenalty(Penalty.NONE) },
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
                            onClick = { viewModel.saveSolveWithPenalty(Penalty.PLUS_TWO) },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "+2",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalButton(
                            onClick = { viewModel.saveSolveWithPenalty(Penalty.DNF) },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "DNF",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        FilledTonalButton(
                            onClick = { viewModel.discardSolve() },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
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
private fun TimerDisplay(time: Long, color: Color) {
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
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Text(
            text = mainText,
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = millisecondsText,
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

@Composable
private fun AveragesDisplay(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    val ao5 = if (solves.size >= 5) {
        val last5 = solves.takeLast(5)
        val validLast5 = last5.filter { it.penalty != Penalty.DNF }
        if (validLast5.size >= 3) {
            validLast5.map { it.displayTime }.average().toLong()
        } else null
    } else null
    
    val ao12 = if (solves.size >= 12) {
        val last12 = solves.takeLast(12)
        val validLast12 = last12.filter { it.penalty != Penalty.DNF }
        if (validLast12.size >= 10) {
            validLast12.map { it.displayTime }.average().toLong()
        } else null
    } else null

    if (ao5 == null && ao12 == null) {
        return
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (ao5 != null) {
                AverageStat(label = "Ao5", time = ao5)
            }
            if (ao12 != null) {
                AverageStat(label = "Ao12", time = ao12)
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
    val contentPadding = 20.dp * safeScale
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
            .padding(horizontal = 12.dp)
            .then(
                if (isTruncated) {
                    Modifier.clickable { showFullScramble = true }
                } else {
                    Modifier
                }
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
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
                    modifier = Modifier.size(buttonSize),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
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
                TextButton(onClick = { showFullScramble = false }) {
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
                .background(Color.Black.copy(alpha = 0.75f))
                .pointerInput(Unit) {
                    detectTapGestures {
                        onDismiss()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Confetti animation
            ConfettiAnimation()
            
            celebration?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(32.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 24.dp
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Text(
                            text = "🎉",
                            fontSize = 72.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Text(
                            text = "NEW RECORD!",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Text(
                                text = when (it.type) {
                                    RecordType.BEST_SINGLE -> "Best Single"
                                    RecordType.BEST_AO5 -> "Best Average of 5"
                                    RecordType.BEST_AO12 -> "Best Average of 12"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = formatDisplayTime(it.time),
                            fontSize = 56.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 56.sp,
                            softWrap = true,
                            maxLines = 2,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .fillMaxWidth()
                        )
                        Text(
                            text = "Tap anywhere to continue",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}
data class ConfettiParticle(
    val initialX: Float,
    val initialY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val color: Color,
    val size: Float,
    val rotationSpeed: Float
)

@Composable
private fun ConfettiAnimation() {
    val density = LocalDensity.current
    var progress by remember { mutableStateOf(0f) }
    
    val confettiColors = listOf(
        Color(0xFFFF6B6B),
        Color(0xFF4ECDC4),
        Color(0xFFFFE66D),
        Color(0xFF95E1D3),
        Color(0xFFF38181),
        Color(0xFFAA96DA),
        Color(0xFFFCACA3),
        Color(0xFFFFBE76)
    )
    
    val particles = remember {
        List(100) {
            ConfettiParticle(
                initialX = Random.nextFloat(),
                initialY = -0.1f,
                velocityX = (Random.nextFloat() - 0.5f) * 2f,
                velocityY = Random.nextFloat() * 0.5f + 0.3f,
                color = confettiColors.random(),
                size = Random.nextFloat() * 8f + 4f,
                rotationSpeed = (Random.nextFloat() - 0.5f) * 720f
            )
        }
    }
    
    LaunchedEffect(Unit) {
        val duration = 3000L
        val startTime = System.currentTimeMillis()
        while (progress < 1f) {
            val elapsed = System.currentTimeMillis() - startTime
            progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            delay(16)
        }
    }
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        particles.forEach { particle ->
            val x = width * particle.initialX + particle.velocityX * width * progress
            val y = height * particle.initialY + particle.velocityY * height * progress + 
                    0.5f * 800f * progress * progress // gravity effect
            
            val rotation = particle.rotationSpeed * progress
            val alpha = (1f - progress).coerceIn(0f, 1f)
            
            if (y < height + 50f) {
                rotate(rotation, pivot = Offset(x, y)) {
                    drawRect(
                        color = particle.color.copy(alpha = alpha),
                        topLeft = Offset(x - particle.size / 2, y - particle.size / 2),
                        size = Size(particle.size, particle.size * 1.5f)
                    )
                }
            }
        }
    }
}
