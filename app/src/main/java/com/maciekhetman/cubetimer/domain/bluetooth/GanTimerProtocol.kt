package com.maciekhetman.cubetimer.domain.bluetooth

import java.util.UUID

/**
 * GAN Smart Timer / GAN Halo wire format. Reference: afedotov/gan-web-bluetooth and csTimer.
 *
 * State notifications on [STATE_CHARACTERISTIC_UUID] look like
 * `FE <len> <?> <state> [min sec msLo msHi] <crcLo crcHi>`, where the CRC-16/CCITT-FALSE covers
 * every byte between the two-byte header and the trailing CRC.
 */
object GanTimerProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
    val NAME_PREFIXES = listOf("GAN", "Gan", "gan")

    private const val HEADER = 0xFE
    private const val STATE_STOPPED = 4

    fun crc16ccitt(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            }
        }
        return crc and 0xFFFF
    }

    /** Decodes one state notification, or returns null for malformed/irrelevant packets. */
    fun parse(packet: ByteArray): SmartTimerEvent? {
        if (packet.size < 6 || packet.u8(0) != HEADER) return null
        val crc = packet.u8(packet.size - 2) or (packet.u8(packet.size - 1) shl 8)
        if (crc != crc16ccitt(packet, from = 2, to = packet.size - 2)) return null
        return when (packet.u8(3)) {
            1 -> SmartTimerEvent.GetSet
            2 -> SmartTimerEvent.HandsOff
            3 -> SmartTimerEvent.Running
            STATE_STOPPED -> {
                if (packet.size < 10) return null
                val minutes = packet.u8(4)
                val seconds = packet.u8(5)
                val millis = packet.u8(6) or (packet.u8(7) shl 8)
                SmartTimerEvent.Stopped(minutes * 60_000L + seconds * 1_000L + millis)
            }
            5 -> SmartTimerEvent.Idle
            6 -> SmartTimerEvent.HandsOn
            // 0 = the timer reports its own disconnect, 7 = "finished" (always follows STOPPED).
            else -> null
        }
    }
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
