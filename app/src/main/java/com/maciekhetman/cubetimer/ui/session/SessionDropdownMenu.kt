package com.maciekhetman.cubetimer.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind

@Composable
fun SessionDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    currentMode: Mode,
    activeSession: Session?,
    isAutomaticMode: Boolean,
    onSwitchToAutomatic: () -> Unit,
    sessions: List<Session>,
    onSessionSelected: (Session) -> Unit,
    onCreateSessionClick: () -> Unit,
    onManageSessionsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val menuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.primary,
        trailingIconColor = MaterialTheme.colorScheme.primary
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.widthIn(min = 220.dp, max = 320.dp)
    ) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = "Active Session (${currentMode.displayName})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        DropdownMenuItem(
            text = {
                Text(
                    text = "Automatic Sessions",
                    fontWeight = if (isAutomaticMode) FontWeight.Bold else FontWeight.Normal
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            },
            trailingIcon = if (isAutomaticMode) {
                { Icon(Icons.Default.Check, contentDescription = "Active") }
            } else null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSwitchToAutomatic()
                onDismissRequest()
            },
            colors = menuItemColors
        )

        val manualSessions = sessions.filter { it.kind == SessionKind.MANUAL }
        if (manualSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            manualSessions.forEach { session ->
                val isSelected = !isAutomaticMode && activeSession?.id == session.id
                DropdownMenuItem(
                    text = {
                        Text(
                            text = session.name,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null
                        )
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSessionSelected(session)
                        onDismissRequest()
                    },
                    colors = menuItemColors
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        DropdownMenuItem(
            text = { Text("+ New Manual Session") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDismissRequest()
                onCreateSessionClick()
            },
            colors = menuItemColors
        )

        DropdownMenuItem(
            text = { Text("Manage All Sessions...") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDismissRequest()
                onManageSessionsClick()
            },
            colors = menuItemColors
        )
    }
}
