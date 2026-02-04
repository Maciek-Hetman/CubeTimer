package com.maciekhetman.cubetimer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maciekhetman.cubetimer.ui.screens.StatsScreen
import com.maciekhetman.cubetimer.ui.screens.TimerScreen
import com.maciekhetman.cubetimer.ui.theme.CubeTimerTheme

class MainActivity : ComponentActivity() {
    private lateinit var timerViewModel: TimerViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        timerViewModel = ViewModelProvider(this)[TimerViewModel::class.java]
        
        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            CubeTimerTheme {
                CubeTimerApp(timerViewModel)
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
fun CubeTimerApp(viewModel: TimerViewModel) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.TIMER) }
    val currentMode by viewModel.currentMode.collectAsState()
    val haptic = LocalHapticFeedback.current
    ApplyStatusBarColor()

    // Predictive back navigation support
    BackHandler(enabled = currentDestination != AppDestinations.TIMER) {
        currentDestination = AppDestinations.TIMER
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { 
                        if (currentDestination != it) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentDestination = it
                        }
                    }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val contentModifier = Modifier.padding(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding(),
                top = 0.dp
            )

            AnimatedContent(
                targetState = currentDestination,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        // Moving forward (Timer -> Stats)
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        // Moving backward (Stats -> Timer)
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
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
}
