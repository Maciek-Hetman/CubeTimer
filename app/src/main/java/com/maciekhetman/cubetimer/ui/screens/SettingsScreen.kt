package com.maciekhetman.cubetimer.ui.screens

import android.content.res.Resources
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NewReleases
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.BuildConfig
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.TIMER_START_DELAY_MAX_MILLIS
import com.maciekhetman.cubetimer.model.TIMER_START_DELAY_STEP_MILLIS
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.model.TimerAverageOptions
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.currentUser
import com.maciekhetman.cubetimer.ui.bluetooth.BluetoothTimerDialog
import com.maciekhetman.cubetimer.ui.bluetooth.bluetoothStatusLabel
import com.maciekhetman.cubetimer.ui.components.CollapsingTopBar
import com.maciekhetman.cubetimer.ui.dialogs.OpenSourceLicensesDialog
import com.maciekhetman.cubetimer.ui.dialogs.ReleaseNotesDialog
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
    var showReleaseNotesDialog by remember { mutableStateOf(value = false) }

    val openUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (e: IllegalArgumentException) {
            // Thrown when nothing on the device can open a web link.
            Toast.makeText(context, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
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
                title = stringResource(R.string.nav_settings),
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
                    onReleaseNotesClick = { showReleaseNotesDialog = true },
                    onLicensesClick = { showLicensesDialog = true },
                    modifier = sectionModifier
                )
            }
        }
    }

    if (showBluetoothDialog) {
        BluetoothTimerDialog(viewModel = viewModel, onDismiss = { showBluetoothDialog = false })
    }

    if (showReleaseNotesDialog) {
        ReleaseNotesDialog(onDismiss = { showReleaseNotesDialog = false })
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
            title = user?.email ?: stringResource(R.string.account_sign_in),
            titleStyle = MaterialTheme.typography.titleMedium,
            titleMaxLines = 1,
            titleOverflow = TextOverflow.MiddleEllipsis,
            supportingText = when (authState) {
                is AuthState.Authenticated, is AuthState.Admin -> stringResource(R.string.account_signed_in)
                AuthState.Guest, AuthState.Loading -> stringResource(R.string.account_guest_pitch)
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
                title = stringResource(R.string.sync_cloud_sync),
                leadingContent = { SyncStatusBadge(syncUiState) },
                supportingContent = {
                    Text(
                        text = syncStatusLabel(LocalResources.current, syncUiState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (syncUiState.conflictCount > 0) {
                        Text(
                            text = syncConflictHint(LocalResources.current, syncUiState.conflictCount),
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

/**
 * How solves are timed: touch or a Bluetooth timer, and touch timing's start delay and inspection. The
 * start delay is also the hold that starts a solve from inspection (0 ms: a tap).
 */
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

    // With a Bluetooth timer the screen is no timer input, so start delay and inspection don't apply.
    val bluetoothSelected = timingDevice == TimingDevice.EXTERNAL_TIMER
    // As on the timer screen: no Bluetooth timer without BLE, unless one is already selected.
    val bluetoothAvailable = bluetoothSelected || bluetoothTimerState.status != BluetoothTimerStatus.Unsupported

    val touchOnlyNote = stringResource(R.string.settings_touch_only_note)
    SettingsSection(title = stringResource(R.string.settings_section_timing), modifier = modifier) {
        SettingChoiceRow(
            title = stringResource(R.string.settings_timing_device),
            icon = if (bluetoothSelected) Icons.Filled.Bluetooth else Icons.Filled.TouchApp,
            supportingText = if (bluetoothAvailable) null else stringResource(R.string.settings_bluetooth_unsupported),
            options = TimingDeviceOptions,
            selected = if (bluetoothSelected) TimingDevice.EXTERNAL_TIMER else TimingDevice.KEYBOARD,
            optionLabel = {
                stringResource(
                    if (it == TimingDevice.EXTERNAL_TIMER) R.string.timing_device_bluetooth else R.string.timing_device_touch
                )
            },
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
            title = stringResource(R.string.settings_start_delay),
            icon = Icons.Filled.PanTool,
            supportingText = if (bluetoothSelected) touchOnlyNote else null,
            value = timerStartDelayMillis,
            valueRange = 0f..TIMER_START_DELAY_MAX_MILLIS.toFloat(),
            steps = TIMER_START_DELAY_MAX_MILLIS / TIMER_START_DELAY_STEP_MILLIS - 1,
            enabled = !bluetoothSelected,
            onValueChangeFinished = viewModel::setTimerStartDelayMillis
        )
        SettingToggleRow(
            title = stringResource(R.string.settings_inspection),
            icon = Icons.Filled.HourglassTop,
            supportingText = if (bluetoothSelected) touchOnlyNote else null,
            checked = inspectionEnabled,
            enabled = !bluetoothSelected,
            onCheckedChange = viewModel::setInspectionEnabled
        )
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
        title = stringResource(R.string.bluetooth_timer),
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

    val focusModeNote = stringResource(R.string.settings_focus_mode_note)
    SettingsSection(title = stringResource(R.string.settings_section_while_solving), modifier = modifier) {
        SettingToggleRow(
            title = stringResource(R.string.settings_focus_mode),
            icon = Icons.Filled.CenterFocusStrong,
            supportingText = stringResource(R.string.settings_focus_mode_summary),
            checked = focusMode,
            onCheckedChange = viewModel::setFocusMode
        )
        SettingChoiceRow(
            title = stringResource(R.string.settings_running_time),
            icon = Icons.Filled.Timer,
            supportingText = if (focusMode) focusModeNote else null,
            options = RunningTimerDisplay.entries,
            selected = runningTimerDisplay,
            // Samples of what the timer shows; screen readers get the full names.
            optionLabel = { display ->
                when (display) {
                    RunningTimerDisplay.FULL -> "12.34"
                    RunningTimerDisplay.SECONDS_ONLY -> "12"
                    RunningTimerDisplay.HIDDEN -> stringResource(R.string.settings_running_time_hidden)
                }
            },
            optionContentDescription = { stringResource(it.labelRes) },
            enabled = !focusMode,
            onSelect = viewModel::setRunningTimerDisplay
        )
        SettingMultiChoiceRow(
            title = stringResource(R.string.settings_show_while_solving),
            icon = Icons.Filled.Visibility,
            supportingText = if (focusMode) focusModeNote else null,
            options = SolveOverlay.entries,
            isSelected = { overlay ->
                when (overlay) {
                    SolveOverlay.SCRAMBLE -> !hideScrambleDuringSolve
                    SolveOverlay.AVERAGES -> !hideAveragesDuringSolve
                    SolveOverlay.LAST_RESULTS -> !hideLastResultsDuringSolve
                }
            },
            optionLabel = { stringResource(it.labelRes) },
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
private enum class SolveOverlay(@StringRes val labelRes: Int) {
    SCRAMBLE(R.string.settings_overlay_scramble),
    AVERAGES(R.string.settings_overlay_averages),
    LAST_RESULTS(R.string.settings_overlay_last_results)
}

/** The timer screen between solves: the scramble, the averages and last results, and the start hint. */
@Composable
private fun TimerScreenSection(viewModel: TimerViewModel, modifier: Modifier = Modifier) {
    val scrambleScalePercent by viewModel.scrambleScalePercent.collectAsStateWithLifecycle()
    val showScrambleRefreshButton by viewModel.showScrambleRefreshButton.collectAsStateWithLifecycle()
    val timerAverages by viewModel.timerAverages.collectAsStateWithLifecycle()
    val hideLastResultsOnTimer by viewModel.hideLastResultsOnTimer.collectAsStateWithLifecycle()
    val hideStartHint by viewModel.hideStartHint.collectAsStateWithLifecycle()

    SettingsSection(title = stringResource(R.string.settings_section_timer_screen), modifier = modifier) {
        SettingSliderRow(
            title = stringResource(R.string.settings_scramble_size),
            icon = Icons.Filled.FormatSize,
            value = scrambleScalePercent,
            valueRange = 70f..140f,
            steps = 13,
            valueFormatter = { stringResource(R.string.settings_value_percent, it) },
            onValueChangeFinished = { percent -> viewModel.setScrambleScalePercent(percent) }
        )
        SettingToggleRow(
            title = stringResource(R.string.settings_new_scramble_button),
            icon = Icons.Filled.Refresh,
            checked = showScrambleRefreshButton,
            onCheckedChange = viewModel::setShowScrambleRefreshButton
        )
        SettingMultiChoiceRow(
            title = stringResource(R.string.settings_averages),
            icon = Icons.Filled.Functions,
            options = TimerAverageOptions,
            isSelected = { it in timerAverages },
            optionLabel = { stringResource(R.string.average_of_n, it) },
            onSelectedChange = viewModel::setTimerAverageEnabled
        )
        // Stored as "hide" flags; shown the positive way round so on means visible.
        SettingToggleRow(
            title = stringResource(R.string.settings_last_results),
            icon = Icons.Filled.FormatListNumbered,
            checked = !hideLastResultsOnTimer,
            onCheckedChange = { show -> viewModel.setHideLastResultsOnTimer(!show) }
        )
        SettingToggleRow(
            title = stringResource(R.string.settings_start_hint),
            icon = Icons.Filled.TipsAndUpdates,
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

    SettingsSection(title = stringResource(R.string.settings_section_appearance), modifier = modifier) {
        if (dynamicColorSupported) {
            SettingToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                icon = Icons.Filled.Palette,
                checked = dynamicColorEnabled,
                onCheckedChange = viewModel::setDynamicColorEnabled
            )
        }
        SettingToggleRow(
            title = stringResource(R.string.settings_amoled),
            icon = Icons.Filled.DarkMode,
            supportingText = if (dynamicColorActive) {
                stringResource(R.string.settings_amoled_unavailable)
            } else {
                stringResource(R.string.settings_amoled_summary)
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

    SettingsSection(title = stringResource(R.string.settings_section_general), modifier = modifier) {
        SettingChipChoiceRow(
            title = stringResource(R.string.settings_default_puzzle),
            icon = Icons.Filled.ViewInAr,
            options = Mode.entries,
            selected = defaultMode,
            optionLabel = { it.displayName },
            onSelect = viewModel::setDefaultMode
        )
        SettingToggleRow(
            title = stringResource(R.string.settings_haptics),
            icon = Icons.Filled.Vibration,
            checked = hapticsEnabled,
            onCheckedChange = viewModel::setHapticsEnabled
        )
    }
}

/**
 * The "About" section: app version, the release notes (via [onReleaseNotesClick]), links to the website, the source code and the issue tracker, and
 * a second group with the privacy policy, the open-source licenses and the web page for deleting an
 * account. Links are handed to [onOpenUrl].
 */
@Composable
fun AboutSection(
    versionName: String,
    onOpenUrl: (String) -> Unit,
    onReleaseNotesClick: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            SettingsItem(
                title = stringResource(R.string.settings_version),
                icon = Icons.Filled.Info,
                supportingText = versionName
            )
            SettingLinkRow(
                title = stringResource(R.string.settings_release_notes),
                icon = Icons.Filled.NewReleases,
                opensExternally = false,
                onClick = onReleaseNotesClick
            )
            SettingLinkRow(title = stringResource(R.string.settings_website), icon = Icons.Filled.Language, onClick = { onOpenUrl(ABOUT_URL) })
            SettingLinkRow(title = stringResource(R.string.settings_source_code), icon = Icons.Filled.Code, onClick = { onOpenUrl(SOURCE_CODE_URL) })
            SettingLinkRow(title = stringResource(R.string.settings_report_problem), icon = Icons.Filled.BugReport, onClick = { onOpenUrl(ISSUES_URL) })
        }
        SettingsSection(title = null) {
            SettingLinkRow(
                title = stringResource(R.string.settings_privacy_policy),
                icon = Icons.Filled.PrivacyTip,
                onClick = { onOpenUrl(PRIVACY_POLICY_URL) }
            )
            SettingLinkRow(
                title = stringResource(R.string.settings_licenses),
                icon = Icons.Filled.Gavel,
                opensExternally = false,
                onClick = onLicensesClick
            )
            SettingLinkRow(
                title = stringResource(R.string.settings_delete_account_web),
                icon = Icons.Filled.PersonRemove,
                onClick = { onOpenUrl(ACCOUNT_DELETION_URL) }
            )
        }
    }
}

fun syncStatusLabel(resources: Resources, syncUiState: SyncUiState): String = when (syncUiState.status) {
    SyncStatusType.SYNCED -> resources.getString(R.string.sync_status_synced)
    SyncStatusType.SYNCING -> resources.getString(R.string.sync_status_syncing)
    SyncStatusType.OFFLINE -> if (syncUiState.pendingCount > 0) {
        resources.getQuantityString(R.plurals.sync_status_offline_pending, syncUiState.pendingCount, syncUiState.pendingCount)
    } else {
        resources.getString(R.string.sync_status_offline)
    }
    SyncStatusType.ERROR -> resources.getString(R.string.sync_status_error)
}

/** "1 conflict needs attention" / "3 conflicts need attention" - shown under the Cloud Sync row. */
fun syncConflictHint(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.sync_conflicts_need_attention, count, count)

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
