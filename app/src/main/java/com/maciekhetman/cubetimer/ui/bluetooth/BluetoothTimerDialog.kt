package com.maciekhetman.cubetimer.ui.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerState
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.data.bluetooth.DiscoveredTimer
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel

/** Short connection summary shared by Settings and the timer header. */
@Composable
fun bluetoothStatusLabel(status: BluetoothTimerStatus): String = when (status) {
    BluetoothTimerStatus.Unsupported -> stringResource(R.string.bt_status_unsupported)
    BluetoothTimerStatus.Disconnected -> stringResource(R.string.bt_status_disconnected)
    BluetoothTimerStatus.Scanning -> stringResource(R.string.bt_status_searching)
    is BluetoothTimerStatus.Connecting -> stringResource(R.string.bt_status_connecting)
    is BluetoothTimerStatus.Connected -> status.deviceName
}

@Composable
fun BluetoothTimerDialog(viewModel: TimerViewModel, onDismiss: () -> Unit) {
    val state by viewModel.bluetoothTimerState.collectAsStateWithLifecycle()
    BluetoothTimerDialog(
        state = state,
        requiredPermissions = viewModel.bluetoothPermissions,
        hasPermissions = viewModel::hasBluetoothPermissions,
        isBluetoothEnabled = viewModel::isBluetoothEnabled,
        onStartScan = viewModel::startBluetoothScan,
        onStopScan = viewModel::stopBluetoothScan,
        onConnect = viewModel::connectBluetoothTimer,
        onDisconnect = viewModel::disconnectBluetoothTimer,
        onClearError = viewModel::clearBluetoothError,
        onDismiss = onDismiss
    )
}

/**
 * Finds and connects a GAN or QiYi timer: asks for the Bluetooth permissions, offers to switch
 * Bluetooth on, scans while open and connects to the tapped timer.
 */
@Composable
fun BluetoothTimerDialog(
    state: BluetoothTimerState,
    requiredPermissions: List<String>,
    hasPermissions: () -> Boolean,
    isBluetoothEnabled: () -> Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    var permissionDenied by remember { mutableStateOf(false) }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isBluetoothEnabled()) onStartScan()
    }

    fun scanOrEnable() {
        onClearError()
        if (isBluetoothEnabled()) {
            onStartScan()
        } else {
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                onStartScan() // reports "Turn on Bluetooth" through the manager's error state
            }
        }
    }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionDenied = grants.values.any { !it }
        if (!permissionDenied) scanOrEnable()
    }

    fun start() {
        if (hasPermissions()) scanOrEnable() else requestPermissions.launch(requiredPermissions.toTypedArray())
    }

    val status = state.status
    LaunchedEffect(Unit) {
        if (status == BluetoothTimerStatus.Disconnected) start()
    }

    val dismiss = {
        onStopScan()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismiss,
        icon = {
            Icon(
                imageVector = when (status) {
                    is BluetoothTimerStatus.Connected -> Icons.Filled.BluetoothConnected
                    BluetoothTimerStatus.Unsupported -> Icons.Filled.BluetoothDisabled
                    else -> Icons.Filled.Bluetooth
                },
                contentDescription = null
            )
        },
        title = { Text(stringResource(R.string.bluetooth_timer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (status) {
                    BluetoothTimerStatus.Unsupported -> Text(stringResource(R.string.bt_dialog_unsupported))
                    is BluetoothTimerStatus.Connected -> Text(
                        stringResource(R.string.bt_dialog_connected, status.deviceName, status.model.displayName)
                    )
                    is BluetoothTimerStatus.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.bt_dialog_connecting, status.deviceName))
                    }
                    BluetoothTimerStatus.Scanning, BluetoothTimerStatus.Disconnected -> DiscoveryContent(
                        scanning = status == BluetoothTimerStatus.Scanning,
                        timers = state.discovered,
                        onConnect = onConnect
                    )
                }
                val error = state.error ?: if (permissionDenied) {
                    stringResource(R.string.bt_dialog_permission_denied)
                } else {
                    null
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (status) {
                is BluetoothTimerStatus.Connected -> TextButton(onClick = onDisconnect) { Text(stringResource(R.string.bt_disconnect)) }
                BluetoothTimerStatus.Disconnected -> TextButton(onClick = { start() }) { Text(stringResource(R.string.bt_search)) }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(if (status is BluetoothTimerStatus.Connected) R.string.action_done else R.string.action_close)) }
        }
    )
}

@Composable
private fun DiscoveryContent(
    scanning: Boolean,
    timers: List<DiscoveredTimer>,
    onConnect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = stringResource(
                    when {
                        scanning && timers.isEmpty() -> R.string.bt_dialog_looking
                        timers.isEmpty() -> R.string.bt_dialog_none_found
                        else -> R.string.bt_dialog_tap_to_connect
                    }
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (timers.isEmpty()) {
            Text(
                text = stringResource(
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        R.string.bt_dialog_scan_hint_location
                    } else {
                        R.string.bt_dialog_scan_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(timers, key = { it.address }) { timer ->
                    Surface(
                        onClick = { onConnect(timer.address) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Filled.Bluetooth, contentDescription = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = timer.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = timer.model.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
