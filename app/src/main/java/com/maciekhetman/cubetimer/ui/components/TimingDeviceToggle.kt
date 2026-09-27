package com.maciekhetman.cubetimer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.ui.bluetooth.bluetoothStatusLabel

/**
 * Two-segment switch between touch timing and a Bluetooth timer, sized to sit next to the mode menu in
 * [TimerTopHeader]. The Bluetooth segment's icon reflects the connection. A click is reported even for
 * the segment that is already selected, so the caller can reopen the connect dialog from it.
 */
@Composable
fun TimingDeviceToggle(
    timingDevice: TimingDevice,
    bluetoothStatus: BluetoothTimerStatus,
    onDeviceClick: (TimingDevice) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val bluetoothSelected = timingDevice == TimingDevice.EXTERNAL_TIMER
    val colors = MaterialTheme.colorScheme

    val connected = bluetoothStatus is BluetoothTimerStatus.Connected
    // Selected but with nothing connected or connecting: the timer screen can't be used until fixed.
    val bluetoothUnavailable = bluetoothSelected &&
        (bluetoothStatus == BluetoothTimerStatus.Disconnected || bluetoothStatus == BluetoothTimerStatus.Unsupported)
    val bluetoothIcon = when {
        connected -> Icons.Filled.BluetoothConnected
        bluetoothUnavailable -> Icons.Filled.BluetoothDisabled
        else -> Icons.Filled.Bluetooth
    }
    val bluetoothTint = when {
        !bluetoothSelected -> colors.onSurfaceVariant
        connected -> colors.primary
        bluetoothUnavailable -> colors.error
        else -> colors.onSecondaryContainer
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TimingDeviceSegment(
                selected = !bluetoothSelected,
                enabled = enabled,
                icon = Icons.Filled.TouchApp,
                contentDescription = "Touch timing",
                tint = if (bluetoothSelected) colors.onSurfaceVariant else colors.onSecondaryContainer,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeviceClick(TimingDevice.KEYBOARD)
                }
            )
            TimingDeviceSegment(
                selected = bluetoothSelected,
                enabled = enabled,
                icon = bluetoothIcon,
                contentDescription = if (bluetoothSelected) {
                    "Bluetooth timer, ${bluetoothStatusLabel(bluetoothStatus)}"
                } else {
                    "Bluetooth timer"
                },
                tint = bluetoothTint,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeviceClick(TimingDevice.EXTERNAL_TIMER)
                }
            )
        }
    }
}

@Composable
private fun TimingDeviceSegment(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "timing_device_segment_background"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 40.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(18.dp)
        )
    }
}
