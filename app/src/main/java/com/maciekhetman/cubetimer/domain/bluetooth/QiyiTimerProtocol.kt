package com.maciekhetman.cubetimer.domain.bluetooth

import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * QiYi Smart Timer / QiYi timer adapter wire format, ported from CubeTimer-web
 * (src/features/timer/bluetooth/qiyiTimer.ts, itself based on csTimer's qiyitimer.js).
 *
 * A message is `sendSn(4) ackSn(4) cmd(2) len(2) payload(len) crc16modbus(2)`, AES-128-ECB
 * encrypted in 16-byte blocks and split into BLE packets: the first packet carries a
 * `00 <msgLen+2> 40 00` header, continuation packets a single sequence byte.
 */
object QiyiTimerProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb")
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000001-0000-1001-8001-00805f9b07d0")
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("00000002-0000-1001-8001-00805f9b07d0")
    const val MANUFACTURER_ID = 0x0504
    val NAME_PREFIXES = listOf("QY-Timer", "QY-Adapter")

    const val CMD_HELLO = 0x0001
    const val CMD_STATE = 0x1003

    private val KEY = ByteArray(16) { 0x77 }

    fun crc16modbus(data: IntArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i] and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc
    }

    internal fun encryptBlock(block: IntArray): IntArray = aes(Cipher.ENCRYPT_MODE, block)

    internal fun decryptBlock(block: IntArray): IntArray = aes(Cipher.DECRYPT_MODE, block)

    private fun aes(mode: Int, block: IntArray): IntArray {
        require(block.size == 16) { "AES block must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(KEY, "AES"))
        val out = cipher.doFinal(ByteArray(16) { block[it].toByte() })
        return IntArray(16) { out[it].toInt() and 0xFF }
    }

    /** Builds the encrypted BLE packets (at most 20 bytes each) for one message. */
    fun encodeMessage(sendSn: Long, ackSn: Long, cmd: Int, payload: IntArray): List<ByteArray> {
        val msg = ArrayList<Int>(14 + payload.size)
        msg += u32(sendSn)
        msg += u32(ackSn)
        msg += listOf((cmd shr 8) and 0xFF, cmd and 0xFF, (payload.size shr 8) and 0xFF, payload.size and 0xFF)
        msg += payload.toList()
        val crc = crc16modbus(msg.toIntArray())
        msg += listOf((crc shr 8) and 0xFF, crc and 0xFF)

        return (msg.indices step 16).map { start ->
            val block = IntArray(16) { i -> msg.getOrElse(start + i) { 1 } }
            val header = if (start == 0) listOf(0x00, msg.size + 2, 0x40, 0x00) else listOf(start shr 4)
            (header + encryptBlock(block).toList()).map { it.toByte() }.toByteArray()
        }
    }

    data class Message(val sendSn: Long, val ackSn: Long, val cmd: Int, val payload: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Message && sendSn == other.sendSn && ackSn == other.ackSn && cmd == other.cmd &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int = ((sendSn.hashCode() * 31 + ackSn.hashCode()) * 31 + cmd) * 31 +
            payload.contentHashCode()
    }

    /** A decoded timer state update, and whether the timer expects an acknowledgement for it. */
    data class ParsedState(val event: SmartTimerEvent?, val needsAck: Boolean)

    fun parseState(message: Message): ParsedState {
        if (message.cmd != CMD_STATE) return ParsedState(null, needsAck = false)
        val data = message.payload
        if (data.size < 2) return ParsedState(null, needsAck = false)
        val dpId = data[0]
        val dpType = data[1]
        if (dpId == 1 && dpType == 1) {
            // A recorded solve: delivered once, and must be acknowledged.
            if (data.size < 12) return ParsedState(null, needsAck = false)
            return ParsedState(SmartTimerEvent.Stopped(readU32(data, 8)), needsAck = true)
        }
        if (dpId == 4 && dpType == 4 && data.size >= 5) {
            val event = when (data[4]) {
                0 -> SmartTimerEvent.Idle
                1 -> SmartTimerEvent.Inspection
                2 -> SmartTimerEvent.GetSet
                3 -> SmartTimerEvent.Running
                4, 5 -> if (data.size >= 9) SmartTimerEvent.Stopped(readU32(data, 5)) else null // finished / stopped
                6 -> SmartTimerEvent.Disconnected
                else -> null
            }
            return ParsedState(event, needsAck = false)
        }
        return ParsedState(null, needsAck = false)
    }

    fun buildHelloPayload(mac: String): IntArray {
        val bytes = parseMac(mac) ?: throw IllegalArgumentException("Invalid MAC address: $mac")
        return intArrayOf(0, 0, 0, 0, 0, 0x21, 0x08, 0, 1, 5, 0x5A) + bytes.reversedArray()
    }

    /** Acknowledges [message] (a recorded solve) so the timer stops re-sending it. */
    fun encodeAck(message: Message): List<ByteArray> =
        encodeMessage(sendSn = message.ackSn + 1, ackSn = message.sendSn, cmd = CMD_STATE, payload = intArrayOf(0x00))

    fun parseMac(mac: String): IntArray? {
        val parts = mac.trim().split(':', '-')
        if (parts.size != 6 || parts.any { !it.matches(Regex("[0-9a-fA-F]{2}")) }) return null
        return IntArray(6) { parts[it].toInt(16) }
    }

    /** MAC advertised in the QiYi manufacturer data (first six bytes, little-endian). */
    fun macFromManufacturerData(data: ByteArray?): String? {
        if (data == null || data.size < 6) return null
        return (5 downTo 0).joinToString(":") { String.format(Locale.US, "%02X", data[it].toInt() and 0xFF) }
    }

    /** MAC implied by the advertised name, e.g. "QY-Adapter-1A2B" -> CC:A8:00:00:1A:2B. */
    fun macFromName(name: String?): String? {
        val match = Regex("^QY-(Timer|Adapter).*-([0-9A-F]{4})$", RegexOption.IGNORE_CASE)
            .find(name?.trim().orEmpty()) ?: return null
        val prefix = if (match.groupValues[1].equals("adapter", ignoreCase = true)) "CC:A8" else "CC:A1"
        val suffix = match.groupValues[2].uppercase(Locale.US)
        return "$prefix:00:00:${suffix.substring(0, 2)}:${suffix.substring(2, 4)}"
    }

    private fun u32(value: Long): List<Int> = listOf(
        ((value ushr 24) and 0xFF).toInt(),
        ((value ushr 16) and 0xFF).toInt(),
        ((value ushr 8) and 0xFF).toInt(),
        (value and 0xFF).toInt()
    )

    internal fun readU32(data: IntArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
}

/** Reassembles and decrypts the packets arriving on the QiYi notify characteristic. */
class QiyiPacketDecoder {
    private var expectedPacket = 0
    private var messageLength = 0
    private val buffer = ArrayList<Int>()

    private fun reset() {
        expectedPacket = 0
        buffer.clear()
    }

    /** Feeds one notification; returns a message once all of its packets have arrived. */
    fun push(packet: ByteArray): QiyiTimerProtocol.Message? {
        if (packet.isEmpty()) return null
        val index = packet[0].toInt() and 0xFF
        if (index != expectedPacket) {
            reset()
            if (index != 0) return null
        }
        val body: List<Int>
        if (index == 0) {
            if (packet.size < 4) return null
            messageLength = (packet[1].toInt() and 0xFF) - 2
            body = packet.drop(4).map { it.toInt() and 0xFF }
        } else {
            body = packet.drop(1).map { it.toInt() and 0xFF }
        }
        for (start in body.indices step 16) {
            if (start + 16 > body.size) {
                reset()
                return null
            }
            buffer += QiyiTimerProtocol.decryptBlock(body.subList(start, start + 16).toIntArray()).toList()
        }
        if (buffer.size < messageLength) {
            expectedPacket++
            return null
        }
        val data = buffer.subList(0, messageLength.coerceAtLeast(0)).toIntArray()
        reset()

        if (data.size < 14) return null
        val length = (data[10] shl 8) or data[11]
        if (data.size < length + 14) return null
        val crc = QiyiTimerProtocol.crc16modbus(data, from = 0, to = length + 12)
        if (crc != ((data[length + 12] shl 8) or data[length + 13])) return null
        return QiyiTimerProtocol.Message(
            sendSn = QiyiTimerProtocol.readU32(data, 0),
            ackSn = QiyiTimerProtocol.readU32(data, 4),
            cmd = (data[8] shl 8) or data[9],
            payload = data.copyOfRange(12, 12 + length)
        )
    }
}
