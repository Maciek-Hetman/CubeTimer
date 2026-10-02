package com.maciekhetman.cubetimer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.ui.bluetooth.bluetoothStatusLabel

/**
 * Two-segment switch between touch timing and a Bluetooth timer, sized to sit next to the mode menu in
 * [TimerTopHeader]. The selected segment is filled with the accent color and labelled, so the active
 * device reads at a glance; the unselected one is icon-only. The Bluetooth segment's icon reflects the
 * connection, and when it is selected with nothing connected it switches to the error colors. A click is
 * reported even for the segment that is already selected, so the caller can reopen the connect dialog
 * from it.
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

    val connected = bluetoothStatus is BluetoothTimerStatus.Connected
    // Selected but with nothing connected or connecting: the timer screen can't be used until fixed.
    val bluetoothUnavailable = bluetoothSelected &&
        (bluetoothStatus == BluetoothTimerStatus.Disconnected || bluetoothStatus == BluetoothTimerStatus.Unsupported)
    val bluetoothIcon = when {
        connected -> Icons.Filled.BluetoothConnected
        bluetoothUnavailable -> Icons.Filled.BluetoothDisabled
        else -> Icons.Filled.Bluetooth
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimingDeviceSegment(
                selected = !bluetoothSelected,
                enabled = enabled,
                icon = Icons.Filled.TouchApp,
                label = stringResource(R.string.timing_device_touch),
                contentDescription = stringResource(R.string.timing_device_touch_a11y),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeviceClick(TimingDevice.KEYBOARD)
                }
            )
            TimingDeviceSegment(
                selected = bluetoothSelected,
                enabled = enabled,
                icon = bluetoothIcon,
                label = stringResource(R.string.timing_device_bluetooth),
                contentDescription = if (bluetoothSelected) {
                    stringResource(R.string.timing_device_bluetooth_a11y_status, bluetoothStatusLabel(bluetoothStatus))
                } else {
                    stringResource(R.string.bluetooth_timer)
                },
                isError = bluetoothUnavailable,
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
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    isError: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    // Solid accent fill for the selected segment: the old secondaryContainer highlight matched the
    // surfaceContainerHigh track exactly in the light scheme, so the selection didn't show at all.
    val targetContainer = when {
        !selected -> Color.Transparent
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        isError -> colors.errorContainer
        else -> colors.primary
    }
    val targetContent = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        !selected -> colors.onSurfaceVariant
        isError -> colors.onErrorContainer
        else -> colors.onPrimary
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = tween(durationMillis = 200),
        label = "timing_device_segment_container"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContent,
        animationSpec = tween(durationMillis = 200),
        label = "timing_device_segment_content"
    )

    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        AnimatedVisibility(visible = selected) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}
