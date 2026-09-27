package com.maciekhetman.cubetimer.data.bluetooth

import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface BluetoothTimerStatus {
    /** The device has no Bluetooth LE support. */
    data object Unsupported : BluetoothTimerStatus
    data object Disconnected : BluetoothTimerStatus
    data object Scanning : BluetoothTimerStatus
    data class Connecting(val deviceName: String) : BluetoothTimerStatus
    data class Connected(val deviceName: String, val model: SmartTimerModel) : BluetoothTimerStatus
}

data class DiscoveredTimer(
    val address: String,
    val name: String,
    val model: SmartTimerModel,
    val rssi: Int
)

data class BluetoothTimerState(
    val status: BluetoothTimerStatus = BluetoothTimerStatus.Disconnected,
    val discovered: List<DiscoveredTimer> = emptyList(),
    val error: String? = null
) {
    val isConnected: Boolean get() = status is BluetoothTimerStatus.Connected
}

/** Scans for, connects to and streams events from a GAN or QiYi Bluetooth timer. */
interface BluetoothTimerManager {
    val state: StateFlow<BluetoothTimerState>

    /** Timer events from the connected timer (hot; nothing is replayed to late collectors). */
    val events: Flow<SmartTimerEvent>

    /** Runtime permissions needed before [startScan] / [connect] on this Android version. */
    val requiredPermissions: List<String>

    fun hasPermissions(): Boolean

    fun isBluetoothEnabled(): Boolean

    fun startScan()

    fun stopScan()

    fun connect(address: String)

    fun disconnect()

    fun clearError()
}
