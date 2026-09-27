package com.maciekhetman.cubetimer.data.bluetooth

import com.maciekhetman.cubetimer.domain.bluetooth.GanTimerProtocol
import com.maciekhetman.cubetimer.domain.bluetooth.QiyiPacketDecoder
import com.maciekhetman.cubetimer.domain.bluetooth.QiyiTimerProtocol
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerModel
import java.util.UUID

/** Write side of an open GATT link (the Android transport in production, a fake in tests). */
fun interface TimerLinkWriter {
    suspend fun write(packet: ByteArray)
}

/**
 * Per-model protocol driver: which GATT characteristics to use, what to send once notifications
 * are on, and how to turn notifications into [SmartTimerEvent]s. Holds no Android types.
 */
interface SmartTimerDriver {
    val model: SmartTimerModel
    val serviceUuid: UUID
    val notifyCharacteristicUuid: UUID
    val writeCharacteristicUuid: UUID?

    /** Called once notifications are enabled. */
    suspend fun onConnected(writer: TimerLinkWriter) = Unit

    /** Decodes one notification (acknowledging it through [writer] if the protocol requires). */
    suspend fun onNotification(packet: ByteArray, writer: TimerLinkWriter): SmartTimerEvent?
}

class GanTimerDriver : SmartTimerDriver {
    override val model = SmartTimerModel.GAN
    override val serviceUuid: UUID = GanTimerProtocol.SERVICE_UUID
    override val notifyCharacteristicUuid: UUID = GanTimerProtocol.STATE_CHARACTERISTIC_UUID
    override val writeCharacteristicUuid: UUID? = null

    override suspend fun onNotification(packet: ByteArray, writer: TimerLinkWriter): SmartTimerEvent? =
        GanTimerProtocol.parse(packet)
}

/**
 * QiYi timers ignore everything until they receive a "hello" carrying their own MAC address, and
 * re-send each recorded solve until it is acknowledged.
 */
class QiyiTimerDriver(private val mac: String) : SmartTimerDriver {
    override val model = SmartTimerModel.QIYI
    override val serviceUuid: UUID = QiyiTimerProtocol.SERVICE_UUID
    override val notifyCharacteristicUuid: UUID = QiyiTimerProtocol.NOTIFY_CHARACTERISTIC_UUID
    override val writeCharacteristicUuid: UUID = QiyiTimerProtocol.WRITE_CHARACTERISTIC_UUID

    private val decoder = QiyiPacketDecoder()

    init {
        require(QiyiTimerProtocol.parseMac(mac) != null) { "Invalid QiYi timer MAC address: $mac" }
    }

    override suspend fun onConnected(writer: TimerLinkWriter) {
        val hello = QiyiTimerProtocol.encodeMessage(
            sendSn = 1,
            ackSn = 0,
            cmd = QiyiTimerProtocol.CMD_HELLO,
            payload = QiyiTimerProtocol.buildHelloPayload(mac)
        )
        hello.forEach { writer.write(it) }
    }

    override suspend fun onNotification(packet: ByteArray, writer: TimerLinkWriter): SmartTimerEvent? {
        val message = decoder.push(packet) ?: return null
        val parsed = QiyiTimerProtocol.parseState(message)
        if (parsed.needsAck) {
            QiyiTimerProtocol.encodeAck(message).forEach { writer.write(it) }
        }
        return parsed.event
    }
}

/** Identifies a supported timer from its advertisement, or null for anything else. */
object SmartTimerDetector {
    fun detect(name: String?, serviceUuids: Collection<UUID>, hasQiyiManufacturerData: Boolean): SmartTimerModel? {
        val trimmed = name?.trim().orEmpty()
        return when {
            QiyiTimerProtocol.NAME_PREFIXES.any { trimmed.startsWith(it) } -> SmartTimerModel.QIYI
            GanTimerProtocol.NAME_PREFIXES.any { trimmed.startsWith(it) } -> SmartTimerModel.GAN
            hasQiyiManufacturerData && QiyiTimerProtocol.SERVICE_UUID in serviceUuids -> SmartTimerModel.QIYI
            // 0xFFF0 is a generic vendor UUID, so only trust it for devices that advertise no name.
            trimmed.isEmpty() && GanTimerProtocol.SERVICE_UUID in serviceUuids -> SmartTimerModel.GAN
            else -> null
        }
    }

    /**
     * The MAC a QiYi hello must carry: the one the timer advertises in its manufacturer data,
     * else the address Android reports (unlike Web Bluetooth, Android exposes it), else the one
     * implied by the advertised name.
     */
    fun qiyiMac(manufacturerData: ByteArray?, deviceAddress: String?, name: String?): String? =
        QiyiTimerProtocol.macFromManufacturerData(manufacturerData)
            ?: deviceAddress?.takeIf { QiyiTimerProtocol.parseMac(it) != null }?.uppercase()
            ?: QiyiTimerProtocol.macFromName(name)
}
