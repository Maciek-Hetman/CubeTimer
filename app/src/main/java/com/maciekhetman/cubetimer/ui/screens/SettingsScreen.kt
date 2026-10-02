package com.maciekhetman.cubetimer.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.BuildConfig
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.InspectionStartGesture
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.model.TimerAverageOptions
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.currentUser
import com.maciekhetman.cubetimer.ui.bluetooth.BluetoothTimerDialog
import com.maciekhetman.cubetimer.ui.bluetooth.bluetoothStatusLabel
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import com.maciekhetman.cubetimer.ui.dialogs.OpenSourceLicensesDialog
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val SOURCE_CODE_URL = "https://github.com/Maciek-Hetman/CubeTimer"
const val ISSUES_URL = "$SOURCE_CODE_URL/issues"
const val LICENSE_URL = "$SOURCE_CODE_URL/blob/main/LICENSE"

/** The web client (CubeTimer-web); its pages below are what the About section links to. */
const val WEBSITE_URL = "https://cubetimer.cc"
const val ABOUT_URL = "$WEBSITE_URL/about"
const val PRIVACY_POLICY_URL = "$WEBSITE_URL/privacy"

/** Where an account can be deleted without the app (its Account page); Play asks for such a link. */
const val ACCOUNT_DELETION_URL = "$WEBSITE_URL/account"

/** How wide the settings column grows on tablets and unfolded foldables. */
private val SettingsMaxWidth = 640.dp

private val TimingDeviceOptions = listOf(TimingDevice.KEYBOARD, TimingDevice.EXTERNAL_TIMER)

/** Supporting text of the settings that only touch timing uses. */
private const val TOUCH_ONLY_NOTE = "Not used with a Bluetooth timer"

/** Supporting text of the settings that focus mode overrides while solving. */
private const val FOCUS_MODE_NOTE = "Focus mode hides everything"

/**
 * Settings: the account card on top, then the timer's settings grouped by when they apply (timing,
 * while solving, the timer screen between solves), then appearance, general and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TimerViewModel,
    modifier: Modifier = Modifier,
    syncUiState: SyncUiState = SyncUiState(),
    onSyncClick: () -> Unit = {},
    authState: AuthState = AuthState.Guest,
    onAuthClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    var showBluetoothDialog by remember { mutableStateOf(value = false) }
    var showLicensesDialog by remember { mutableStateOf(value = false) }

    val openUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: IllegalArgumentException) {
            // Thrown when nothing on the device can open a web link.
            Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // No mode menu: nothing here depends on the current mode, and "Default puzzle" below
            // would read as a second picker for the same thing.
            CollapsingTopBar(
                title = "Settings",
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        val sectionModifier = Modifier
            .widthIn(max = SettingsMaxWidth)
            .fillMaxWidth()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(layoutDirection),
                top = paddingValues.calculateTopPadding() + 8.dp,
                end = paddingValues.calculateEndPadding(layoutDirection),
                bottom = paddingValues.calculateBottomPadding() + 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "account") {
                AccountSection(
                    syncUiState = syncUiState,
                    onSyncClick = onSyncClick,
                    authState = authState,
                    onAuthClick = onAuthClick,
                    modifier = sectionModifier
                )
            }
            item(key = "timing") {
                TimingSection(
                    viewModel = viewModel,
                    onManageBluetoothTimer = { showBluetoothDialog = true },
                    modifier = sectionModifier
                )
            }
            item(key = "while_solving") {
                WhileSolvingSection(viewModel = viewModel, modifier = sectionModifier)
            }
            item(key = "timer_screen") {
                TimerScreenSection(viewModel = viewModel, modifier = sectionModifier)
            }
            item(key = "appearance") {
                AppearanceSection(viewModel = viewModel, modifier = sectionModifier)
            }
            item(key = "general") {
                GeneralSection(viewModel = viewModel, modifier = sectionModifier)
            }
            item(key = "about") {
                AboutSection(
                    versionName = BuildConfig.VERSION_NAME,
                    onOpenUrl = openUrl,
                    onLicensesClick = { showLicensesDialog = true },
                    modifier = sectionModifier
                )
            }
        }
    }

    if (showBluetoothDialog) {
        BluetoothTimerDialog(viewModel = viewModel, onDismiss = { showBluetoothDialog = false })
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(
            onViewLicense = { openUrl(LICENSE_URL) },
            onDismiss = { showLicensesDialog = false }
        )
    }
}

/**
 * The account card heading Settings, and below it, once signed in, the cloud sync status (with a hint
 * when sync conflicts need the user's attention). Tapping a row opens the matching dialog via the
 * callbacks.
 */
@Composable
fun AccountSection(
    syncUiState: SyncUiState,
    onSyncClick: () -> Unit,
    authState: AuthState,
    onAuthClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val user = authState.currentUser
    SettingsSection(title = null, modifier = modifier) {
        SettingsItem(
            title = user?.email ?: "Sign in",
            titleStyle = MaterialTheme.typography.titleMedium,
            titleMaxLines = 1,
            titleOverflow = TextOverflow.MiddleEllipsis,
            supportingText = when (authState) {
                is AuthState.Admin -> "Signed in · Admin"
                is AuthState.Authenticated -> "Signed in"
                AuthState.Guest, AuthState.Loading -> "Back up your solves and sync them across devices"
            },
            leadingContent = { AccountAvatar(authState) },
            trailingContent = { SettingsChevron() },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onAuthClick()
            }
        )
        if (user != null) {
            SettingsItem(
                title = "Cloud Sync",
                leadingContent = { SyncStatusBadge(syncUiState) },
                supportingContent = {
                    Text(
                        text = syncStatusLabel(syncUiState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (syncUiState.conflictCount > 0) {
                        Text(
                            text = syncConflictHint(syncUiState.conflictCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                trailingContent = { SettingsChevron() },
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSyncClick()
                }
            )
        }
    }
}

/** How solves are timed: touch or a Bluetooth timer, and touch timing's start delay and inspection. */
@Composable
private fun TimingSection(
    viewModel: TimerViewModel,
    onManageBluetoothTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timingDevice by viewModel.timingDevice.collectAsStateWithLifecycle()
    val bluetoothTimerState by viewModel.bluetoothTimerState.collectAsStateWithLifecycle()
    val timerStartDelayMillis by viewModel.timerStartDelayMillis.collectAsStateWithLifecycle()
    val inspectionEnabled by viewModel.inspectionEnabled.collectAsStateWithLifecycle()
    val inspectionStartGesture by viewModel.inspectionStartGesture.collectAsStateWithLifecycle()

    // With a Bluetooth timer the screen is no timer input, so start delay and inspection don't apply.
    val bluetoothSelected = timingDevice == TimingDevice.EXTERNAL_TIMER
    // As on the timer screen: no Bluetooth timer without BLE, unless one is already selected.
    val bluetoothAvailable = bluetoothSelected || bluetoothTimerState.status != BluetoothTimerStatus.Unsupported

    SettingsSection(title = "Timing", modifier = modifier) {
        SettingChoiceRow(
            title = "Timing device",
            icon = if (bluetoothSelected) Icons.Filled.Bluetooth else Icons.Filled.TouchApp,
            supportingText = if (bluetoothAvailable) {
                "Bluetooth works with GAN and QiYi timers"
            } else {
                "Bluetooth timers aren't supported on this device"
            },
            options = TimingDeviceOptions,
            selected = if (bluetoothSelected) TimingDevice.EXTERNAL_TIMER else TimingDevice.KEYBOARD,
            optionLabel = { if (it == TimingDevice.EXTERNAL_TIMER) "Bluetooth" else "Touch" },
            optionIcon = { if (it == TimingDevice.EXTERNAL_TIMER) Icons.Filled.Bluetooth else Icons.Filled.TouchApp },
            isOptionEnabled = { it != TimingDevice.EXTERNAL_TIMER || bluetoothAvailable },
            onSelect = { device ->
                viewModel.setTimingDevice(device)
                if (device == TimingDevice.EXTERNAL_TIMER && !bluetoothTimerState.isConnected) {
                    onManageBluetoothTimer()
                }
            }
        )
        AnimatedSettingsItem(visible = bluetoothSelected) {
            BluetoothTimerRow(status = bluetoothTimerState.status, onClick = onManageBluetoothTimer)
        }
        SettingSliderRow(
            title = "Start delay",
            icon = Icons.Filled.PanTool,
            supportingText = if (bluetoothSelected) TOUCH_ONLY_NOTE else "Hold time before the timer is ready",
            value = timerStartDelayMillis,
            valueRange = 200f..1000f,
            steps = 7,
            enabled = !bluetoothSelected,
            onValueChangeFinished = viewModel::setTimerStartDelayMillis
        )
        SettingToggleRow(
            title = "Inspection",
            icon = Icons.Filled.HourglassTop,
            supportingText = if (bluetoothSelected) TOUCH_ONLY_NOTE else "15 s countdown before each solve",
            checked = inspectionEnabled,
            enabled = !bluetoothSelected,
            onCheckedChange = viewModel::setInspectionEnabled
        )
        AnimatedSettingsItem(visible = inspectionEnabled) {
            SettingChoiceRow(
                title = "Start solve with",
                icon = Icons.Filled.Gesture,
                supportingText = "Once inspection is running",
                options = InspectionStartGesture.entries,
                selected = inspectionStartGesture,
                optionLabel = { it.displayName },
                enabled = !bluetoothSelected,
                onSelect = viewModel::setInspectionStartGesture
            )
        }
    }
}

/** The connected Bluetooth timer, or what is keeping one from connecting; opens the connect dialog. */
@Composable
private fun BluetoothTimerRow(status: BluetoothTimerStatus, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    // Selected with nothing connected or connecting: no solve can start until that is fixed.
    val unavailable = status == BluetoothTimerStatus.Disconnected || status == BluetoothTimerStatus.Unsupported
    SettingsItem(
        title = "Bluetooth timer",
        supportingText = bluetoothStatusLabel(status),
        supportingTextColor = when {
            status is BluetoothTimerStatus.Connected -> colors.primary
            unavailable -> colors.error
            else -> Color.Unspecified
        },
        leadingContent = {
            SettingsIcon(
                imageVector = when (status) {
                    is BluetoothTimerStatus.Connected -> Icons.Filled.BluetoothConnected
                    BluetoothTimerStatus.Disconnected, BluetoothTimerStatus.Unsupported -> Icons.Filled.BluetoothDisabled
                    BluetoothTimerStatus.Scanning, is BluetoothTimerStatus.Connecting -> Icons.Filled.BluetoothSearching
                },
                tint = if (unavailable) colors.onErrorContainer else colors.primary,
                containerColor = if (unavailable) colors.errorContainer else colors.surfaceContainerHighest
            )
        },
        trailingContent = { SettingsChevron() },
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
    )
}

/** What the timer screen keeps showing while the timer runs. */
@Composable
private fun WhileSolvingSection(viewModel: TimerViewModel, modifier: Modifier = Modifier) {
    val focusMode by viewModel.focusMode.collectAsStateWithLifecycle()
    val runningTimerDisplay by viewModel.runningTimerDisplay.collectAsStateWithLifecycle()
    val hideScrambleDuringSolve by viewModel.hideScrambleDuringSolve.collectAsStateWithLifecycle()
    val hideAveragesDuringSolve by viewModel.hideAveragesDuringSolve.collectAsStateWithLifecycle()
    val hideLastResultsDuringSolve by viewModel.hideLastResultsDuringSolve.collectAsStateWithLifecycle()

    SettingsSection(title = "While solving", modifier = modifier) {
        SettingToggleRow(
            title = "Focus mode",
            icon = Icons.Filled.CenterFocusStrong,
            supportingText = "Blank screen while the timer runs",
            checked = focusMode,
            onCheckedChange = viewModel::setFocusMode
        )
        SettingChoiceRow(
            title = "Running time",
            icon = Icons.Filled.Timer,
            supportingText = if (focusMode) FOCUS_MODE_NOTE else null,
            options = RunningTimerDisplay.entries,
            selected = runningTimerDisplay,
            // Samples of what the timer shows; screen readers get the full names.
            optionLabel = { display ->
                when (display) {
                    RunningTimerDisplay.FULL -> "12.34"
                    RunningTimerDisplay.SECONDS_ONLY -> "12"
                    RunningTimerDisplay.HIDDEN -> "Hidden"
                }
            },
            optionContentDescription = { it.displayName },
            enabled = !focusMode,
            onSelect = viewModel::setRunningTimerDisplay
        )
        SettingMultiChoiceRow(
            title = "Show while solving",
            icon = Icons.Filled.Visibility,
            supportingText = if (focusMode) FOCUS_MODE_NOTE else null,
            options = SolveOverlay.entries,
            isSelected = { overlay ->
                when (overlay) {
                    SolveOverlay.SCRAMBLE -> !hideScrambleDuringSolve
                    SolveOverlay.AVERAGES -> !hideAveragesDuringSolve
                    SolveOverlay.LAST_RESULTS -> !hideLastResultsDuringSolve
                }
            },
            optionLabel = { it.label },
            enabled = !focusMode,
            onSelectedChange = { overlay, show ->
                when (overlay) {
                    SolveOverlay.SCRAMBLE -> viewModel.setHideScrambleDuringSolve(!show)
                    SolveOverlay.AVERAGES -> viewModel.setHideAveragesDuringSolve(!show)
                    SolveOverlay.LAST_RESULTS -> viewModel.setHideLastResultsDuringSolve(!show)
                }
            }
        )
    }
}

/** The parts of the timer screen that "Show while solving" can hide while the timer runs. */
private enum class SolveOverlay(val label: String) {
    SCRAMBLE("Scramble"),
    AVERAGES("Averages"),
    LAST_RESULTS("Last results")
}

/** The timer screen between solves: the scramble, the averages and last results, and the start hint. */
@Composable
private fun TimerScreenSection(viewModel: TimerViewModel, modifier: Modifier = Modifier) {
    val scrambleScalePercent by viewModel.scrambleScalePercent.collectAsStateWithLifecycle()
    val showScrambleRefreshButton by viewModel.showScrambleRefreshButton.collectAsStateWithLifecycle()
    val timerAverages by viewModel.timerAverages.collectAsStateWithLifecycle()
    val hideLastResultsOnTimer by viewModel.hideLastResultsOnTimer.collectAsStateWithLifecycle()
    val hideStartHint by viewModel.hideStartHint.collectAsStateWithLifecycle()

    SettingsSection(title = "Timer screen", modifier = modifier) {
        SettingSliderRow(
            title = "Scramble size",
            icon = Icons.Filled.FormatSize,
            value = scrambleScalePercent,
            valueRange = 70f..140f,
            steps = 13,
            valueFormatter = { "$it%" },
            onValueChangeFinished = { percent -> viewModel.setScrambleScalePercent(percent) }
        )
        SettingToggleRow(
            title = "New scramble button",
            icon = Icons.Filled.Refresh,
            supportingText = "Shown next to the scramble",
            checked = showScrambleRefreshButton,
            onCheckedChange = viewModel::setShowScrambleRefreshButton
        )
        SettingMultiChoiceRow(
            title = "Averages",
            icon = Icons.Filled.Functions,
            supportingText = "Shown below the timer",
            options = TimerAverageOptions,
            isSelected = { it in timerAverages },
            optionLabel = { "Ao$it" },
            onSelectedChange = viewModel::setTimerAverageEnabled
        )
        // Stored as "hide" flags; shown the positive way round so on means visible.
        SettingToggleRow(
            title = "Last results",
            icon = Icons.Filled.FormatListNumbered,
            supportingText = "Your latest times below the averages",
            checked = !hideLastResultsOnTimer,
            onCheckedChange = { show -> viewModel.setHideLastResultsOnTimer(!show) }
        )
        SettingToggleRow(
            title = "Start hint",
            icon = Icons.Filled.TipsAndUpdates,
            supportingText = "How to start, shown under the time",
            checked = !hideStartHint,
            onCheckedChange = { show -> viewModel.setHideStartHint(!show) }
        )
    }
}

@Composable
private fun AppearanceSection(viewModel: TimerViewModel, modifier: Modifier = Modifier) {
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val amoledEnabled by viewModel.amoledEnabled.collectAsStateWithLifecycle()
    // Wallpaper colors need Android 12; below that CubeTimerTheme ignores the setting, so it isn't offered.
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicColorActive = dynamicColorSupported && dynamicColorEnabled

    SettingsSection(title = "Appearance", modifier = modifier) {
        if (dynamicColorSupported) {
            SettingToggleRow(
                title = "Dynamic color",
                icon = Icons.Filled.Palette,
                supportingText = "Use colors from your wallpaper",
                checked = dynamicColorEnabled,
                onCheckedChange = viewModel::setDynamicColorEnabled
            )
        }
        SettingToggleRow(
            title = "AMOLED dark",
            icon = Icons.Filled.DarkMode,
            supportingText = if (dynamicColorActive) {
                "Not available with dynamic color"
            } else {
                "Pure black background in dark theme"
            },
            checked = amoledEnabled,
            enabled = !dynamicColorActive,
            onCheckedChange = viewModel::setAmoledEnabled
        )
    }
}

@Composable
private fun GeneralSection(viewModel: TimerViewModel, modifier: Modifier = Modifier) {
    val defaultMode by viewModel.defaultMode.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()

    SettingsSection(title = "General", modifier = modifier) {
        SettingChipChoiceRow(
            title = "Default puzzle",
            icon = Icons.Filled.ViewInAr,
            supportingText = "Selected when the app opens",
            options = Mode.entries,
            selected = defaultMode,
            optionLabel = { it.displayName },
            onSelect = viewModel::setDefaultMode
        )
        SettingToggleRow(
            title = "Haptic feedback",
            icon = Icons.Filled.Vibration,
            supportingText = "Vibrate on taps, timer starts and records",
            checked = hapticsEnabled,
            onCheckedChange = viewModel::setHapticsEnabled
        )
    }
}

/**
 * The "About" section: app version, links to the website, the source code and the issue tracker, and
 * a second group with the privacy policy, the open-source licenses and the web page for deleting an
 * account. Links are handed to [onOpenUrl].
 */
@Composable
fun AboutSection(
    versionName: String,
    onOpenUrl: (String) -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSection(title = "About") {
            SettingsItem(
                title = "Version",
                icon = Icons.Filled.Info,
                supportingText = versionName
            )
            SettingLinkRow(title = "Website", icon = Icons.Filled.Language, onClick = { onOpenUrl(ABOUT_URL) })
            SettingLinkRow(title = "Source code", icon = Icons.Filled.Code, onClick = { onOpenUrl(SOURCE_CODE_URL) })
            SettingLinkRow(title = "Report a problem", icon = Icons.Filled.BugReport, onClick = { onOpenUrl(ISSUES_URL) })
        }
        SettingsSection(title = null) {
            SettingLinkRow(
                title = "Privacy policy",
                icon = Icons.Filled.PrivacyTip,
                onClick = { onOpenUrl(PRIVACY_POLICY_URL) }
            )
            SettingLinkRow(
                title = "Open-source licenses",
                icon = Icons.Filled.Gavel,
                opensExternally = false,
                onClick = onLicensesClick
            )
            SettingLinkRow(
                title = "Delete account on the web",
                icon = Icons.Filled.PersonRemove,
                onClick = { onOpenUrl(ACCOUNT_DELETION_URL) }
            )
        }
    }
}

fun syncStatusLabel(syncUiState: SyncUiState): String = when (syncUiState.status) {
    SyncStatusType.SYNCED -> "Synced"
    SyncStatusType.SYNCING -> "Syncing…"
    SyncStatusType.OFFLINE -> if (syncUiState.pendingCount > 0) "Offline (${syncUiState.pendingCount} pending)" else "Offline"
    SyncStatusType.ERROR -> "Sync error"
}

/** "1 conflict needs attention" / "3 conflicts need attention" - shown under the Cloud Sync row. */
fun syncConflictHint(count: Int): String =
    if (count == 1) "1 conflict needs attention" else "$count conflicts need attention"

/**
 * The account card's avatar: the user's initial on a primary "cookie", or a person on a neutral one for
 * a guest. Admins get a shield badge.
 */
@Composable
private fun AccountAvatar(authState: AuthState) {
    val user = authState.currentUser
    val colors = MaterialTheme.colorScheme
    Box {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = if (user != null) colors.primary else colors.surfaceContainerHighest,
                    shape = AvatarShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (user != null) {
                Text(
                    text = avatarInitial(user),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        if (authState is AuthState.Admin) {
            Icon(
                imageVector = Icons.Filled.AdminPanelSettings,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 4.dp, y = 4.dp)
                    .background(colors.surfaceContainer, CircleShape)
                    .padding(3.dp)
                    .size(18.dp)
            )
        }
    }
}

private fun avatarInitial(user: User): String {
    val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.email
    return name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}

/** The cloud sync state as a leading badge: error colors while sync failed or conflicts wait. */
@Composable
private fun SyncStatusBadge(syncUiState: SyncUiState) {
    if (syncUiState.status == SyncStatusType.SYNCING) {
        SpinningSyncBadge()
        return
    }
    val colors = MaterialTheme.colorScheme
    val needsAttention = syncUiState.status == SyncStatusType.ERROR || syncUiState.conflictCount > 0
    val offline = syncUiState.status == SyncStatusType.OFFLINE
    SettingsIcon(
        imageVector = when {
            needsAttention -> Icons.Filled.SyncProblem
            offline -> Icons.Filled.CloudOff
            else -> Icons.Filled.CloudDone
        },
        tint = when {
            needsAttention -> colors.onErrorContainer
            offline -> colors.onSurfaceVariant
            else -> colors.primary
        },
        containerColor = if (needsAttention) colors.errorContainer else colors.surfaceContainerHighest
    )
}

@Composable
private fun SpinningSyncBadge() {
    val transition = rememberInfiniteTransition(label = "settings_sync_spin")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "settings_sync_rotation"
    )
    // Read in the layer block, so the spin redraws without recomposing.
    SettingsIcon(
        imageVector = Icons.Filled.Sync,
        modifier = Modifier.graphicsLayer { rotationZ = rotation.value }
    )
}

/** Material 3 Expressive's 9-sided "cookie": a circle with soft scallops, the first one pointing up. */
private val AvatarShape: Shape = CookieShape(lobes = 9, depth = 0.1f)

private class CookieShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = minOf(centerX, centerY)
        val segments = lobes * 16
        val path = Path()
        for (i in 0 until segments) {
            val t = 2f * PI.toFloat() * i / segments
            // Full radius at a lobe's tip, `depth` of it further in between lobes.
            val r = radius * (1f - depth / 2f + depth / 2f * cos(lobes * t))
            val angle = t - PI.toFloat() / 2f
            val x = centerX + r * cos(angle)
            val y = centerY + r * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
