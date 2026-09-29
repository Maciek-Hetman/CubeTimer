package com.maciekhetman.cubetimer.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.maciekhetman.cubetimer.domain.bluetooth.QiyiTimerProtocol
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * BLE transport for GAN and QiYi timers. Scans without filters (the timers are recognised by
 * advertised name / service / manufacturer data, which ScanFilter cannot prefix-match), connects
 * over GATT, enables notifications on the driver's characteristic and serialises every GATT write,
 * since Android allows only one outstanding GATT operation per connection.
 *
 * Every entry point checks [hasPermissions] first, hence the class-wide MissingPermission.
 */
@SuppressLint("MissingPermission")
class AndroidBluetoothTimerManager(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : BluetoothTimerManager {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val bleSupported =
        adapter != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private val _state = MutableStateFlow(
        BluetoothTimerState(status = if (bleSupported) BluetoothTimerStatus.Disconnected else BluetoothTimerStatus.Unsupported)
    )
    override val state: StateFlow<BluetoothTimerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SmartTimerEvent>(extraBufferCapacity = 64)
    override val events: Flow<SmartTimerEvent> = _events.asSharedFlow()

    override val requiredPermissions: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Advertisement details of every timer seen during the current scan, keyed by address. */
    private val scanRecords = mutableMapOf<String, ScanDetails>()
    private var scanStopJob: Job? = null
    private var connection: Connection? = null
    private val connectionLock = Any()

    private data class ScanDetails(val name: String, val model: SmartTimerModel, val qiyiManufacturerData: ByteArray?)

    override fun hasPermissions(): Boolean = requiredPermissions.all {
        appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    override fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // --- Scanning -------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onScanResultReceived(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::onScanResultReceived)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _state.update {
                it.copy(status = BluetoothTimerStatus.Disconnected, error = "Bluetooth scan failed (code $errorCode)")
            }
        }
    }

    private fun onScanResultReceived(result: ScanResult) {
        val record = result.scanRecord
        val address = result.device.address ?: return
        val qiyiData = record?.getManufacturerSpecificData(QiyiTimerProtocol.MANUFACTURER_ID)
        val serviceUuids: List<UUID> = record?.serviceUuids?.map { it.uuid }.orEmpty()
        val name = record?.deviceName
        val model = SmartTimerDetector.detect(name, serviceUuids, hasQiyiManufacturerData = qiyiData != null) ?: return
        val displayName = name?.takeIf { it.isNotBlank() } ?: model.displayName

        synchronized(scanRecords) {
            scanRecords[address] = ScanDetails(displayName, model, qiyiData)
        }
        val discovered = DiscoveredTimer(address = address, name = displayName, model = model, rssi = result.rssi)
        _state.update { current ->
            val others = current.discovered.filterNot { it.address == address }
            current.copy(discovered = (others + discovered).sortedByDescending { it.rssi })
        }
    }

    override fun startScan() {
        if (!bleSupported) return
        if (!hasPermissions()) {
            _state.update { it.copy(error = "Bluetooth permission is required to find your timer") }
            return
        }
        if (!isBluetoothEnabled()) {
            _state.update { it.copy(error = "Turn on Bluetooth to find your timer") }
            return
        }
        if (connection != null) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        synchronized(scanRecords) { scanRecords.clear() }
        _state.update { it.copy(status = BluetoothTimerStatus.Scanning, discovered = emptyList(), error = null) }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
        } catch (e: SecurityException) {
            _state.update { it.copy(status = BluetoothTimerStatus.Disconnected, error = "Bluetooth permission was denied") }
            return
        }
        scanStopJob?.cancel()
        scanStopJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
        }
    }

    override fun stopScan() {
        scanStopJob?.cancel()
        scanStopJob = null
        try {
            if (hasPermissions()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan without permission", e)
        } catch (e: IllegalStateException) {
            // Bluetooth was turned off meanwhile; the scan is already gone.
        }
        _state.update { if (it.status == BluetoothTimerStatus.Scanning) it.copy(status = BluetoothTimerStatus.Disconnected) else it }
    }

    // --- Connection -----------------------------------------------------------------------------

    override fun connect(address: String) {
        if (!bleSupported || !hasPermissions()) {
            _state.update { it.copy(error = "Bluetooth permission is required to connect to your timer") }
            return
        }
        stopScan()
        disconnect()

        val details = synchronized(scanRecords) { scanRecords[address] }
        if (details == null) {
            _state.update { it.copy(error = "Timer not found. Scan again and make sure it is switched on.") }
            return
        }
        val driver = when (details.model) {
            SmartTimerModel.GAN -> GanTimerDriver()
            SmartTimerModel.QIYI -> {
                val mac = SmartTimerDetector.qiyiMac(details.qiyiManufacturerData, address, details.name)
                if (mac == null) {
                    _state.update { it.copy(error = "Couldn't read this QiYi timer's address") }
                    return
                }
                QiyiTimerDriver(mac)
            }
        }
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return

        val newConnection = Connection(details.name, driver)
        synchronized(connectionLock) { connection = newConnection }
        _state.update { it.copy(status = BluetoothTimerStatus.Connecting(details.name), error = null) }
        newConnection.open(device)
    }

    override fun disconnect() {
        val current = synchronized(connectionLock) { connection.also { connection = null } } ?: return
        current.close(notify = false)
        _state.update { it.copy(status = BluetoothTimerStatus.Disconnected) }
    }

    /** One GATT connection and its notification pipeline. */
    private inner class Connection(
        private val deviceName: String,
        private val driver: SmartTimerDriver
    ) {
        private var gatt: BluetoothGatt? = null
        private val gattOpLock = Mutex()
        @Volatile private var pendingOp: CompletableDeferred<Boolean>? = null
        private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
        private var worker: Job? = null
        private var connectTimeout: Job? = null
        @Volatile private var closed = false
        @Volatile private var ready = false

        private val writer = TimerLinkWriter { packet -> writeCharacteristic(packet) }

        fun open(device: BluetoothDevice) {
            connectTimeout = scope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (!ready) fail("Couldn't connect to $deviceName")
            }
            gatt = try {
                // The BluetoothGattConnectionSettings overload that replaces this is API 37+ only.
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                null
            }
            if (gatt == null) fail("Couldn't connect to $deviceName")
        }

        fun close(notify: Boolean) {
            if (closed) return
            closed = true
            connectTimeout?.cancel()
            worker?.cancel()
            notifications.close()
            pendingOp?.complete(false)
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "GATT close without permission", e)
            }
            gatt = null
            if (notify && ready) _events.tryEmit(SmartTimerEvent.Disconnected)
        }

        /** Tears the connection down and reports [message] (null for a plain link loss). */
        private fun fail(message: String?) {
            val wasCurrent = synchronized(connectionLock) {
                (connection === this).also { if (it) connection = null }
            }
            close(notify = true)
            if (wasCurrent) {
                _state.update { it.copy(status = BluetoothTimerStatus.Disconnected, error = message) }
            }
        }

        private suspend fun writeCharacteristic(packet: ByteArray) {
            val uuid = driver.writeCharacteristicUuid ?: return
            gattOpLock.withLock {
                val gatt = gatt ?: return
                val characteristic = gatt.getService(driver.serviceUuid)?.getCharacteristic(uuid) ?: return
                val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                val op = CompletableDeferred<Boolean>()
                pendingOp = op
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, packet, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    characteristic.value = packet
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                if (!started) {
                    pendingOp = null
                    Log.w(TAG, "GATT write could not be started")
                    return
                }
                try {
                    withTimeout(GATT_OP_TIMEOUT_MS) { op.await() }
                } catch (e: Exception) {
                    Log.w(TAG, "GATT write did not complete", e)
                } finally {
                    pendingOp = null
                }
            }
        }

        private suspend fun enableNotifications(gatt: BluetoothGatt): Boolean {
            val characteristic = gatt.getService(driver.serviceUuid)?.getCharacteristic(driver.notifyCharacteristicUuid)
                ?: return false
            if (!gatt.setCharacteristicNotification(characteristic, true)) return false
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR) ?: return true
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            return gattOpLock.withLock {
                val op = CompletableDeferred<Boolean>()
                pendingOp = op
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                try {
                    started && withTimeout(GATT_OP_TIMEOUT_MS) { op.await() }
                } catch (e: Exception) {
                    false
                } finally {
                    pendingOp = null
                }
            }
        }

        private fun onServicesReady(gatt: BluetoothGatt) {
            worker = scope.launch {
                if (!enableNotifications(gatt)) {
                    fail("$deviceName doesn't look like a supported timer")
                    return@launch
                }
                ready = true
                connectTimeout?.cancel()
                _state.update {
                    it.copy(status = BluetoothTimerStatus.Connected(deviceName, driver.model), discovered = emptyList(), error = null)
                }
                try {
                    driver.onConnected(writer)
                } catch (e: Exception) {
                    Log.w(TAG, "Timer handshake failed", e)
                }
                for (packet in notifications) {
                    val event = try {
                        driver.onNotification(packet, writer)
                    } catch (e: Exception) {
                        Log.w(TAG, "Dropping undecodable timer packet", e)
                        null
                    }
                    if (event != null) _events.emit(event)
                }
            }
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (closed) return
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.discoverServices()) fail("Couldn't read $deviceName's services")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    fail(if (ready) null else "Couldn't connect to $deviceName")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (closed) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onServicesReady(gatt)
                } else {
                    fail("Couldn't read $deviceName's services")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            // API 33+ delivers the value here; older versions call the deprecated overload below.
            // Not calling super keeps the platform from also invoking the deprecated one.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                onNotification(characteristic.uuid, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                onNotification(characteristic.uuid, value.copyOf())
            }
        }

        private fun onNotification(uuid: UUID, value: ByteArray) {
            if (!closed && uuid == driver.notifyCharacteristicUuid) notifications.trySend(value)
        }
    }

    private companion object {
        const val TAG = "BluetoothTimer"
        const val SCAN_DURATION_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val GATT_OP_TIMEOUT_MS = 3_000L
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
