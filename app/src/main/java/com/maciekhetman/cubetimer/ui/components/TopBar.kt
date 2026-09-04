package com.maciekhetman.cubetimer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.ui.session.SessionDropdownMenu

@Composable
fun TimerTopHeader(
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    activeSession: Session? = null,
    isAutomaticMode: Boolean = true,
    onSwitchToAutomatic: () -> Unit = {},
    sessions: List<Session> = emptyList(),
    onSessionSelected: (Session) -> Unit = {},
    onCreateSessionClick: () -> Unit = {},
    onManageSessionsClick: () -> Unit = {},
    syncUiState: SyncUiState = SyncUiState(),
    onSyncClick: () -> Unit = {},
    authState: AuthState = AuthState.Guest,
    onAuthClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeMenu(
                currentMode = currentMode,
                onModeSelected = onModeSelected
            )
            SessionPillMenu(
                currentMode = currentMode,
                activeSession = activeSession,
                isAutomaticMode = isAutomaticMode,
                onSwitchToAutomatic = onSwitchToAutomatic,
                sessions = sessions,
                onSessionSelected = onSessionSelected,
                onCreateSessionClick = onCreateSessionClick,
                onManageSessionsClick = onManageSessionsClick
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SyncStatusIconButton(
                syncUiState = syncUiState,
                onClick = onSyncClick
            )
            AuthStatusIconButton(
                authState = authState,
                onClick = onAuthClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    activeSession: Session? = null,
    isAutomaticMode: Boolean = true,
    onSwitchToAutomatic: () -> Unit = {},
    sessions: List<Session> = emptyList(),
    onSessionSelected: (Session) -> Unit = {},
    onCreateSessionClick: () -> Unit = {},
    onManageSessionsClick: () -> Unit = {},
    syncUiState: SyncUiState = SyncUiState(),
    onSyncClick: () -> Unit = {},
    authState: AuthState = AuthState.Guest,
    onAuthClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (title.isBlank() || title == "Timer") {
        TimerTopHeader(
            currentMode = currentMode,
            onModeSelected = onModeSelected,
            activeSession = activeSession,
            isAutomaticMode = isAutomaticMode,
            onSwitchToAutomatic = onSwitchToAutomatic,
            sessions = sessions,
            onSessionSelected = onSessionSelected,
            onCreateSessionClick = onCreateSessionClick,
            onManageSessionsClick = onManageSessionsClick,
            syncUiState = syncUiState,
            onSyncClick = onSyncClick,
            authState = authState,
            onAuthClick = onAuthClick,
            modifier = modifier
        )
    } else {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            actions = {
                TopBarActionItems(
                    currentMode = currentMode,
                    onModeSelected = onModeSelected,
                    activeSession = activeSession,
                    isAutomaticMode = isAutomaticMode,
                    onSwitchToAutomatic = onSwitchToAutomatic,
                    sessions = sessions,
                    onSessionSelected = onSessionSelected,
                    onCreateSessionClick = onCreateSessionClick,
                    onManageSessionsClick = onManageSessionsClick,
                    syncUiState = syncUiState,
                    onSyncClick = onSyncClick,
                    authState = authState,
                    onAuthClick = onAuthClick
                )
            },
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTopBar(
    title: String,
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    activeSession: Session? = null,
    isAutomaticMode: Boolean = true,
    onSwitchToAutomatic: () -> Unit = {},
    sessions: List<Session> = emptyList(),
    onSessionSelected: (Session) -> Unit = {},
    onCreateSessionClick: () -> Unit = {},
    onManageSessionsClick: () -> Unit = {},
    syncUiState: SyncUiState = SyncUiState(),
    onSyncClick: () -> Unit = {},
    authState: AuthState = AuthState.Guest,
    onAuthClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    MediumTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            TopBarActionItems(
                currentMode = currentMode,
                onModeSelected = onModeSelected,
                activeSession = activeSession,
                isAutomaticMode = isAutomaticMode,
                onSwitchToAutomatic = onSwitchToAutomatic,
                sessions = sessions,
                onSessionSelected = onSessionSelected,
                onCreateSessionClick = onCreateSessionClick,
                onManageSessionsClick = onManageSessionsClick,
                syncUiState = syncUiState,
                onSyncClick = onSyncClick,
                authState = authState,
                onAuthClick = onAuthClick
            )
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier
    )
}

@Composable
private fun TopBarActionItems(
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit,
    activeSession: Session?,
    isAutomaticMode: Boolean,
    onSwitchToAutomatic: () -> Unit,
    sessions: List<Session>,
    onSessionSelected: (Session) -> Unit,
    onCreateSessionClick: () -> Unit,
    onManageSessionsClick: () -> Unit,
    syncUiState: SyncUiState,
    onSyncClick: () -> Unit,
    authState: AuthState,
    onAuthClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Mode Selector Dropdown
        ModeMenu(
            currentMode = currentMode,
            onModeSelected = onModeSelected
        )

        // Session Selector Dropdown (if session handling is wired)
        SessionPillMenu(
            currentMode = currentMode,
            activeSession = activeSession,
            isAutomaticMode = isAutomaticMode,
            onSwitchToAutomatic = onSwitchToAutomatic,
            sessions = sessions,
            onSessionSelected = onSessionSelected,
            onCreateSessionClick = onCreateSessionClick,
            onManageSessionsClick = onManageSessionsClick
        )

        // Cloud Sync Status Icon
        SyncStatusIconButton(
            syncUiState = syncUiState,
            onClick = onSyncClick
        )

        // User / Profile / Admin Icon
        AuthStatusIconButton(
            authState = authState,
            onClick = onAuthClick
        )
    }
}

@Composable
private fun SessionPillMenu(
    currentMode: Mode,
    activeSession: Session?,
    isAutomaticMode: Boolean,
    onSwitchToAutomatic: () -> Unit,
    sessions: List<Session>,
    onSessionSelected: (Session) -> Unit,
    onCreateSessionClick: () -> Unit,
    onManageSessionsClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val sessionLabel = if (isAutomaticMode) {
        "Auto"
    } else {
        activeSession?.name ?: "Session"
    }

    Box {
        FilledTonalButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                expanded = true
            },
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(
                start = 10.dp,
                top = 6.dp,
                end = 8.dp,
                bottom = 6.dp
            ),
            modifier = Modifier
                .padding(end = 4.dp)
                .widthIn(max = 140.dp)
        ) {
            Icon(
                imageVector = if (isAutomaticMode) Icons.Default.FlashOn else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isAutomaticMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = sessionLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select session",
                modifier = Modifier.size(16.dp)
            )
        }

        SessionDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            currentMode = currentMode,
            activeSession = activeSession,
            isAutomaticMode = isAutomaticMode,
            onSwitchToAutomatic = onSwitchToAutomatic,
            sessions = sessions,
            onSessionSelected = onSessionSelected,
            onCreateSessionClick = onCreateSessionClick,
            onManageSessionsClick = onManageSessionsClick
        )
    }
}

@Composable
private fun SyncStatusIconButton(
    syncUiState: SyncUiState,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "topbar_sync_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "topbar_sync_rotation"
    )

    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
    ) {
        when (syncUiState.status) {
            SyncStatusType.SYNCED -> {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "Synced",
                    tint = if (syncUiState.isGuest) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
            SyncStatusType.SYNCING -> {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Syncing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(rotation)
                )
            }
            SyncStatusType.OFFLINE -> {
                if (syncUiState.pendingCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(syncUiState.pendingCount.coerceAtMost(99).toString())
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = "Offline with pending changes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Offline",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            SyncStatusType.ERROR -> {
                BadgedBox(
                    badge = {
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text("!")
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Sync Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthStatusIconButton(
    authState: AuthState,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
    ) {
        when (authState) {
            is AuthState.Admin -> {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = "Admin Account",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
            is AuthState.Authenticated -> {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "User Profile",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Sign In",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun ModeMenu(
    currentMode: Mode,
    onModeSelected: (Mode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val menuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurface,
        trailingIconColor = MaterialTheme.colorScheme.primary
    )

    Box {
        FilledTonalButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                expanded = true
            },
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 6.dp,
                end = 8.dp,
                bottom = 6.dp
            ),
            modifier = Modifier.padding(end = 4.dp)
        ) {
            Text(
                text = currentMode.displayName,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select mode",
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Mode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onModeSelected(mode)
                        expanded = false
                    },
                    colors = menuItemColors,
                    trailingIcon = if (currentMode == mode) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected mode"
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}
