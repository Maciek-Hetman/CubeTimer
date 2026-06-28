package com.maciekhetman.cubetimer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.ui.screens.StatsScreen
import com.maciekhetman.cubetimer.ui.screens.SettingsScreen
import com.maciekhetman.cubetimer.ui.screens.TimerScreen
import com.maciekhetman.cubetimer.ui.theme.CubeTimerTheme
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel

class MainActivity : ComponentActivity() {
    private lateinit var timerViewModel: TimerViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        timerViewModel = ViewModelProvider(this)[TimerViewModel::class.java]
        
        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            val dynamicColorEnabled by timerViewModel.dynamicColorEnabled.collectAsState()
            val amoledEnabled by timerViewModel.amoledEnabled.collectAsState()
            val hapticsEnabled by timerViewModel.hapticsEnabled.collectAsState()
            CubeTimerTheme(
                dynamicColor = dynamicColorEnabled,
                amoled = amoledEnabled && !dynamicColorEnabled
            ) {
                OptionalHapticsProvider(enabled = hapticsEnabled) {
                    CubeTimerApp(timerViewModel)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        timerViewModel.resetAppStartTime()
    }
    
    override fun onPause() {
        super.onPause()
        timerViewModel.updateAppTime()
    }
}

@Composable
private fun OptionalHapticsProvider(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    if (enabled) {
        content()
    } else {
        CompositionLocalProvider(LocalHapticFeedback provides NoHapticFeedback) {
            content()
        }
    }
}

private object NoHapticFeedback : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@Composable
fun CubeTimerApp(viewModel: TimerViewModel) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.TIMER) }
    val currentMode by viewModel.currentMode.collectAsState()
    val timerState by viewModel.timerState.collectAsState()
    val focusMode by viewModel.focusMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    val isTimerRunning = timerState is TimerState.Running
    val focusModeActive = focusMode && isTimerRunning
    ApplyStatusBarColor()

    // Predictive back navigation support
    BackHandler(enabled = currentDestination != AppDestinations.TIMER && !isTimerRunning) {
        currentDestination = AppDestinations.TIMER
    }

    @Composable
    fun AppContent(innerPadding: PaddingValues) {
        val layoutDirection = LocalLayoutDirection.current
        val contentModifier = Modifier.padding(
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
            bottom = 0.dp,
            top = 0.dp
        )

        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = {
                val springSpec = spring<IntOffset>(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
                val fadeSpec = tween<Float>(durationMillis = 180)
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally(springSpec) { width -> width / 4 } +
                        fadeIn(fadeSpec) +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                        .togetherWith(
                            slideOutHorizontally(springSpec) { width -> -width / 5 } +
                                fadeOut(fadeSpec) +
                                scaleOut(targetScale = 0.98f, animationSpec = tween(180))
                        )
                } else {
                    (slideInHorizontally(springSpec) { width -> -width / 4 } +
                        fadeIn(fadeSpec) +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                        .togetherWith(
                            slideOutHorizontally(springSpec) { width -> width / 5 } +
                                fadeOut(fadeSpec) +
                                scaleOut(targetScale = 0.98f, animationSpec = tween(180))
                        )
                }.using(SizeTransform(clip = false))
            },
            label = "screen_transition"
        ) { destination ->
            when (destination) {
                AppDestinations.TIMER -> {
                    TimerScreen(
                        viewModel = viewModel,
                        currentMode = currentMode,
                        onModeSelected = { mode -> viewModel.setMode(mode) },
                        modifier = contentModifier
                    )
                }
                AppDestinations.STATS -> {
                    LaunchedEffect(Unit) {
                        viewModel.updateAppTime()
                    }
                    StatsScreen(
                        viewModel = viewModel,
                        currentMode = currentMode,
                        onModeSelected = { mode -> viewModel.setMode(mode) },
                        modifier = contentModifier
                    )
                }
                AppDestinations.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        currentMode = currentMode,
                        onModeSelected = { mode -> viewModel.setMode(mode) },
                        modifier = contentModifier
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AppContent(innerPadding)
            
            if (!focusModeActive) {
                FloatingNavigationBar(
                    currentDestination = currentDestination,
                    onNavigate = { currentDestination = it },
                    isTimerRunning = isTimerRunning,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
fun FloatingNavigationBar(
    currentDestination: AppDestinations,
    onNavigate: (AppDestinations) -> Unit,
    isTimerRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppDestinations.entries.forEach { destination ->
                val selected = destination == currentDestination
                val color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                }
                
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1.22f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "nav_icon_scale"
                )

                IconButton(
                    onClick = {
                        if (!isTimerRunning && currentDestination != destination) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNavigate(destination)
                        }
                    },
                    modifier = Modifier.scale(scale)
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                        tint = color,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplyStatusBarColor() {
    val view = LocalView.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceArgb = surfaceColor.toArgb()

    SideEffect {
        val activity = view.context as? ComponentActivity ?: return@SideEffect
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = surfaceArgb,
                darkScrim = surfaceArgb
            )
        )
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    TIMER("Timer", Icons.Default.Home),
    STATS("Stats", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings),
}
