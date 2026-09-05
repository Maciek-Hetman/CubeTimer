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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maciekhetman.cubetimer.data.sync.SyncStateManager
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.ui.auth.AuthDialog
import com.maciekhetman.cubetimer.ui.auth.AuthDialogType
import com.maciekhetman.cubetimer.ui.screens.AdminDashboardScreen
import com.maciekhetman.cubetimer.ui.screens.SettingsScreen
import com.maciekhetman.cubetimer.ui.screens.StatsScreen
import com.maciekhetman.cubetimer.ui.screens.TimerScreen
import com.maciekhetman.cubetimer.ui.session.CreateSessionDialog
import com.maciekhetman.cubetimer.ui.session.DeleteSessionDialog
import com.maciekhetman.cubetimer.ui.session.RenameSessionDialog
import com.maciekhetman.cubetimer.ui.session.SessionManagementSheet
import com.maciekhetman.cubetimer.ui.sync.SyncStatusDialog
import com.maciekhetman.cubetimer.ui.theme.CubeTimerTheme
import com.maciekhetman.cubetimer.viewmodel.AdminViewModel
import com.maciekhetman.cubetimer.viewmodel.AuthViewModel
import com.maciekhetman.cubetimer.viewmodel.SessionViewModel
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel

class MainActivity : ComponentActivity() {
    private lateinit var timerViewModel: TimerViewModel
    private lateinit var sessionViewModel: SessionViewModel
    private lateinit var authViewModel: AuthViewModel
    private lateinit var adminViewModel: AdminViewModel
    private lateinit var syncStateManager: SyncStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as CubeTimerApplication
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(TimerViewModel::class.java) -> {
                        TimerViewModel(
                            application = app,
                            repository = app.solvesRepository,
                            settingsRepository = com.maciekhetman.cubetimer.data.SettingsRepository(app),
                            sessionManager = app.sessionManager,
                            authManager = app.authManager
                        ) as T
                    }
                    modelClass.isAssignableFrom(SessionViewModel::class.java) -> {
                        SessionViewModel(
                            application = app,
                            sessionManager = app.sessionManager,
                            sessionRepository = app.sessionRepository,
                            authManager = app.authManager
                        ) as T
                    }
                    modelClass.isAssignableFrom(AuthViewModel::class.java) -> {
                        AuthViewModel(
                            application = app,
                            authManager = app.authManager
                        ) as T
                    }
                    modelClass.isAssignableFrom(AdminViewModel::class.java) -> {
                        AdminViewModel(
                            application = app,
                            adminRepository = app.adminRepository
                        ) as T
                    }
                    else -> super.create(modelClass)
                }
            }
        }
        timerViewModel = ViewModelProvider(this, factory)[TimerViewModel::class.java]
        sessionViewModel = ViewModelProvider(this, factory)[SessionViewModel::class.java]
        authViewModel = ViewModelProvider(this, factory)[AuthViewModel::class.java]
        adminViewModel = ViewModelProvider(this, factory)[AdminViewModel::class.java]
        syncStateManager = app.syncStateManager

        // Keep screen on while app is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val dynamicColorEnabled by timerViewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
            val amoledEnabled by timerViewModel.amoledEnabled.collectAsStateWithLifecycle()
            val hapticsEnabled by timerViewModel.hapticsEnabled.collectAsStateWithLifecycle()
            CubeTimerTheme(
                dynamicColor = dynamicColorEnabled,
                amoled = amoledEnabled && !dynamicColorEnabled
            ) {
                OptionalHapticsProvider(enabled = hapticsEnabled) {
                    CubeTimerApp(
                        viewModel = timerViewModel,
                        sessionViewModel = sessionViewModel,
                        authViewModel = authViewModel,
                        adminViewModel = adminViewModel,
                        syncStateManager = syncStateManager
                    )
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
fun CubeTimerApp(
    viewModel: TimerViewModel,
    sessionViewModel: SessionViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel(),
    adminViewModel: AdminViewModel = viewModel(),
    syncStateManager: SyncStateManager = (LocalContext.current.applicationContext as? CubeTimerApplication)?.syncStateManager ?: SyncStateManager()
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.TIMER) }
    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val isTimerRunning by viewModel.isTimerRunning.collectAsStateWithLifecycle()
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val focusModeActive = focusMode && isTimerRunning

    // Reactive Auth & Sync States
    val authState by authViewModel.authState.collectAsStateWithLifecycle()
    val authFormState by authViewModel.formState.collectAsStateWithLifecycle()
    val syncUiState by syncStateManager.syncUiState.collectAsStateWithLifecycle()

    // Reactive Session States
    val activeSession by sessionViewModel.activeSession.collectAsStateWithLifecycle()
    val isAutomaticMode by sessionViewModel.isAutomaticMode.collectAsStateWithLifecycle()
    val sessionsList by sessionViewModel.sessionsList.collectAsStateWithLifecycle()
    val archivedSessionsList by sessionViewModel.archivedSessionsList.collectAsStateWithLifecycle()

    // Modals visibility states
    var showSyncDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateSessionDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameSessionDialog by rememberSaveable { mutableStateOf(false) }
    var sessionToRename by remember { mutableStateOf<Session?>(null) }
    var showDeleteSessionDialog by rememberSaveable { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<Session?>(null) }
    var showSessionManagementSheet by rememberSaveable { mutableStateOf(false) }

    ApplyStatusBarColor()

    // Predictive back navigation support
    BackHandler(enabled = currentDestination != AppDestinations.TIMER && !isTimerRunning) {
        if (currentDestination == AppDestinations.ADMIN) {
            currentDestination = AppDestinations.SETTINGS
        } else {
            currentDestination = AppDestinations.TIMER
        }
    }

    val onModeSelected: (com.maciekhetman.cubetimer.model.Mode) -> Unit = { mode ->
        viewModel.setMode(mode)
        sessionViewModel.setMode(mode)
    }

    val onAuthClick: () -> Unit = {
        if (authState is AuthState.Guest) {
            authViewModel.openDialog(AuthDialogType.LOGIN)
        } else {
            authViewModel.openDialog(AuthDialogType.USER_PROFILE)
        }
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
                (fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 60)) +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(durationMillis = 220, delayMillis = 60)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(durationMillis = 140)) +
                            scaleOut(targetScale = 0.98f, animationSpec = tween(durationMillis = 140))
                    ).using(SizeTransform(clip = false))
            },
            label = "screen_fade_through_transition"
        ) { destination ->
            when (destination) {
                AppDestinations.TIMER -> {
                    TimerScreen(
                        viewModel = viewModel,
                        currentMode = currentMode,
                        onModeSelected = onModeSelected,
                        activeSession = activeSession,
                        isAutomaticMode = isAutomaticMode,
                        onSwitchToAutomatic = { sessionViewModel.switchToAutomaticSession() },
                        sessions = sessionsList,
                        onSessionSelected = { session -> sessionViewModel.switchSession(session.id) },
                        onCreateSessionClick = { showCreateSessionDialog = true },
                        onManageSessionsClick = { showSessionManagementSheet = true },
                        syncUiState = syncUiState,
                        onSyncClick = { showSyncDialog = true },
                        authState = authState,
                        onAuthClick = onAuthClick,
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
                        onModeSelected = onModeSelected,
                        activeSession = activeSession,
                        isAutomaticMode = isAutomaticMode,
                        onSwitchToAutomatic = { sessionViewModel.switchToAutomaticSession() },
                        sessions = sessionsList,
                        onSessionSelected = { session -> sessionViewModel.switchSession(session.id) },
                        onCreateSessionClick = { showCreateSessionDialog = true },
                        onManageSessionsClick = { showSessionManagementSheet = true },
                        syncUiState = syncUiState,
                        onSyncClick = { showSyncDialog = true },
                        authState = authState,
                        onAuthClick = onAuthClick,
                        modifier = contentModifier
                    )
                }
                AppDestinations.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        currentMode = currentMode,
                        onModeSelected = onModeSelected,
                        activeSession = activeSession,
                        isAutomaticMode = isAutomaticMode,
                        onSwitchToAutomatic = { sessionViewModel.switchToAutomaticSession() },
                        sessions = sessionsList,
                        onSessionSelected = { session -> sessionViewModel.switchSession(session.id) },
                        onCreateSessionClick = { showCreateSessionDialog = true },
                        onManageSessionsClick = { showSessionManagementSheet = true },
                        syncUiState = syncUiState,
                        onSyncClick = { showSyncDialog = true },
                        authState = authState,
                        onAuthClick = onAuthClick,
                        onNavigateToAdmin = { currentDestination = AppDestinations.ADMIN },
                        modifier = contentModifier
                    )
                }
                AppDestinations.ADMIN -> {
                    AdminDashboardScreen(
                        viewModel = adminViewModel,
                        authState = authState,
                        onNavigateBack = { currentDestination = AppDestinations.SETTINGS },
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

            if (!focusModeActive && currentDestination != AppDestinations.ADMIN) {
                FloatingNavigationBar(
                    currentDestination = currentDestination,
                    onNavigate = { currentDestination = it },
                    isTimerRunning = isTimerRunning,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    // Modal dialogs and bottom sheets
    AuthDialog(
        formState = authFormState,
        authState = authState,
        viewModel = authViewModel,
        onDismiss = { authViewModel.dismissDialog() },
        onOpenAdminDashboard = { currentDestination = AppDestinations.ADMIN }
    )

    if (showSyncDialog) {
        SyncStatusDialog(
            syncState = syncUiState,
            onTriggerSync = { syncStateManager.triggerSync() },
            onDismiss = { showSyncDialog = false },
            onLoginClick = { authViewModel.openDialog(AuthDialogType.LOGIN) }
        )
    }

    if (showCreateSessionDialog) {
        CreateSessionDialog(
            onDismiss = { showCreateSessionDialog = false },
            onConfirm = { name -> sessionViewModel.createManualSession(name) }
        )
    }

    if (showRenameSessionDialog && sessionToRename != null) {
        RenameSessionDialog(
            initialName = sessionToRename!!.name,
            onDismiss = {
                showRenameSessionDialog = false
                sessionToRename = null
            },
            onConfirm = { newName ->
                sessionToRename?.let { session ->
                    sessionViewModel.renameSession(session.id, newName)
                }
                showRenameSessionDialog = false
                sessionToRename = null
            }
        )
    }

    if (showDeleteSessionDialog && sessionToDelete != null) {
        DeleteSessionDialog(
            sessionName = sessionToDelete!!.name,
            onDismiss = {
                showDeleteSessionDialog = false
                sessionToDelete = null
            },
            onConfirm = {
                sessionToDelete?.let { session ->
                    sessionViewModel.deleteSession(session.id)
                }
                showDeleteSessionDialog = false
                sessionToDelete = null
            }
        )
    }

    if (showSessionManagementSheet) {
        SessionManagementSheet(
            onDismissRequest = { showSessionManagementSheet = false },
            currentMode = currentMode,
            activeSession = activeSession,
            isAutomaticMode = isAutomaticMode,
            onSwitchToAutomatic = { sessionViewModel.switchToAutomaticSession() },
            activeSessions = sessionsList,
            archivedSessions = archivedSessionsList,
            onSelectSession = { session -> sessionViewModel.switchSession(session.id) },
            onCreateSession = { showCreateSessionDialog = true },
            onRenameSession = { session ->
                sessionToRename = session
                showRenameSessionDialog = true
            },
            onArchiveSession = { session -> sessionViewModel.archiveSession(session.id) },
            onUnarchiveSession = { session -> sessionViewModel.unarchiveSession(session.id) },
            onDeleteSession = { session ->
                sessionToDelete = session
                showDeleteSessionDialog = true
            }
        )
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
    val density = LocalDensity.current
    val visibleDestinations = listOf(AppDestinations.TIMER, AppDestinations.STATS, AppDestinations.SETTINGS)
    val selectedIndex = visibleDestinations.indexOf(currentDestination).let { if (it >= 0) it else 0 }

    var previousIndex by remember { mutableIntStateOf(selectedIndex) }
    val isMovingRight = selectedIndex >= previousIndex
    SideEffect {
        previousIndex = selectedIndex
    }

    val leftSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = if (isMovingRight) Spring.StiffnessMediumLow else Spring.StiffnessMedium
    )
    val rightSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = if (isMovingRight) Spring.StiffnessMedium else Spring.StiffnessMediumLow
    )

    val indicatorWidth = 64.dp
    val indicatorHeight = 42.dp
    val containerPadding = 5.dp
    val itemGap = 6.dp

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = CircleShape,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                modifier = Modifier.padding(containerPadding)
            ) {
                val targetLeft = (indicatorWidth + itemGap) * selectedIndex
                val targetRight = targetLeft + indicatorWidth

                val targetLeftPx = with(density) { targetLeft.toPx() }
                val targetRightPx = with(density) { targetRight.toPx() }

                val animatedLeft by animateFloatAsState(
                    targetValue = targetLeftPx,
                    animationSpec = leftSpring,
                    label = "nav_indicator_left"
                )
                val animatedRight by animateFloatAsState(
                    targetValue = targetRightPx,
                    animationSpec = rightSpring,
                    label = "nav_indicator_right"
                )

                val pillLeft = animatedLeft
                val pillWidth = (animatedRight - animatedLeft).coerceAtLeast(with(density) { indicatorWidth.toPx() })

                // Animated Material You indicator pill sliding & morphing behind active destination
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(x = pillLeft.roundToInt(), y = 0)
                        }
                        .size(
                            width = with(density) { pillWidth.toDp() },
                            height = indicatorHeight
                        )
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(itemGap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    visibleDestinations.forEach { destination ->
                        val selected = destination == currentDestination
                        val iconColor by animateColorAsState(
                            targetValue = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = tween(durationMillis = 200),
                            label = "nav_icon_color"
                        )

                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "nav_icon_scale"
                        )

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = indicatorWidth, height = indicatorHeight)
                                .clip(CircleShape)
                                .clickable {
                                    if (!isTimerRunning && currentDestination != destination) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onNavigate(destination)
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                tint = iconColor,
                                modifier = Modifier
                                    .size(24.dp)
                                    .scale(scale)
                            )
                        }
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

    LaunchedEffect(surfaceArgb) {
        val activity = view.context as? ComponentActivity ?: return@LaunchedEffect
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
    ADMIN("Admin", Icons.Default.AdminPanelSettings),
}
