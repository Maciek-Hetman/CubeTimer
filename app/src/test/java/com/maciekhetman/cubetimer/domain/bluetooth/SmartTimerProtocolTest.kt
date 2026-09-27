package com.maciekhetman.cubetimer.domain.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Wire-format tests for the GAN and QiYi timer protocols (ported from CubeTimer-web's suite). */
class SmartTimerProtocolTest {

    private fun hex(value: String): IntArray = value.chunked(2).map { it.toInt(16) }.toIntArray()

    // --- AES / CRC primitives -------------------------------------------------------------------

    @Test
    fun jcaAesEcbMatchesTheFips197Vector() {
        // QiYi relies on single-block AES-128-ECB; make sure the platform cipher is what we think.
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex("000102030405060708090a0b0c0d0e0f").map { it.toByte() }.toByteArray(), "AES"))
        val out = cipher.doFinal(hex("00112233445566778899aabbccddeeff").map { it.toByte() }.toByteArray())
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", out.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun qiyiBlockCipherRoundTrips() {
        val block = IntArray(16) { it * 7 and 0xFF }
        assertArrayEquals(block, QiyiTimerProtocol.decryptBlock(QiyiTimerProtocol.encryptBlock(block)))
    }

    @Test
    fun crc16Modbus_checkValue() {
        assertEquals(0x4B37, QiyiTimerProtocol.crc16modbus("123456789".map { it.code }.toIntArray()))
    }

    @Test
    fun crc16Ccitt_checkValue() {
        assertEquals(0x29B1, GanTimerProtocol.crc16ccitt("123456789".toByteArray()))
    }

    // --- QiYi -----------------------------------------------------------------------------------

    @Test
    fun qiyiHello_splitsIntoTwentyBytePacketsWithHeaders() {
        val packets = QiyiTimerProtocol.encodeMessage(1, 0, QiyiTimerProtocol.CMD_HELLO, QiyiTimerProtocol.buildHelloPayload("CC:A8:00:00:12:34"))
        // 12 header + 17 payload + 2 crc = 31 bytes -> two AES blocks
        assertEquals(2, packets.size)
        assertEquals(listOf(0x00, 33, 0x40, 0x00), packets[0].take(4).map { it.toInt() and 0xFF })
        assertEquals(20, packets[0].size)
        assertEquals(1, packets[1][0].toInt())
        assertEquals(17, packets[1].size)
    }

    @Test
    fun qiyiHello_carriesTheMacReversed() {
        val payload = QiyiTimerProtocol.buildHelloPayload("CC:A8:00:00:12:34")
        assertEquals(listOf(0x34, 0x12, 0x00, 0x00, 0xA8, 0xCC), payload.takeLast(6))
    }

    @Test
    fun qiyiDecoder_reassemblesARecordedSolveAndRequestsAck() {
        val payload = intArrayOf(1, 1, 0, 12, 0, 0, 0, 0, 0, 0, 0x30, 0x39, 0, 0, 0x3A, 0x98)
        val packets = QiyiTimerProtocol.encodeMessage(7, 3, QiyiTimerProtocol.CMD_STATE, payload)
        val decoder = QiyiPacketDecoder()
        val results = packets.map { decoder.push(it) }

        assertTrue(results.dropLast(1).all { it == null })
        val message = results.last()!!
        assertEquals(QiyiTimerProtocol.Message(7, 3, QiyiTimerProtocol.CMD_STATE, payload), message)
        assertEquals(
            QiyiTimerProtocol.ParsedState(SmartTimerEvent.Stopped(12_345L), needsAck = true),
            QiyiTimerProtocol.parseState(message)
        )
    }

    @Test
    fun qiyiAck_echoesTheSequenceNumbers() {
        val message = QiyiTimerProtocol.Message(sendSn = 7, ackSn = 3, cmd = QiyiTimerProtocol.CMD_STATE, payload = intArrayOf())
        val decoded = QiyiPacketDecoder().let { decoder -> QiyiTimerProtocol.encodeAck(message).map { decoder.push(it) }.last() }!!
        assertEquals(4L, decoded.sendSn)
        assertEquals(7L, decoded.ackSn)
        assertEquals(QiyiTimerProtocol.CMD_STATE, decoded.cmd)
        assertArrayEquals(intArrayOf(0), decoded.payload)
    }

    @Test
    fun qiyiStatusUpdates_mapToEvents() {
        fun decode(state: Int, time: Int = 0): SmartTimerEvent? {
            val decoder = QiyiPacketDecoder()
            val packets = QiyiTimerProtocol.encodeMessage(1, 1, QiyiTimerProtocol.CMD_STATE, intArrayOf(4, 4, 0, 5, state, 0, 0, (time shr 8) and 0xFF, time and 0xFF))
            return QiyiTimerProtocol.parseState(packets.map { decoder.push(it) }.last()!!).event
        }
        assertEquals(SmartTimerEvent.Idle, decode(0))
        assertEquals(SmartTimerEvent.Inspection, decode(1))
        assertEquals(SmartTimerEvent.GetSet, decode(2))
        assertEquals(SmartTimerEvent.Running, decode(3))
        assertEquals(SmartTimerEvent.Stopped(9_876L), decode(5, 9_876))
        assertNull(decode(42))
    }

    @Test
    fun qiyiDecoder_rejectsABadCrc() {
        // 12 header + 2 payload + 2 crc = exactly one block, so the packet is a complete message
        // and a null result can only come from the CRC check.
        val packet = QiyiTimerProtocol.encodeMessage(1, 1, QiyiTimerProtocol.CMD_STATE, intArrayOf(4, 4)).single()
        assertEquals(1L, QiyiPacketDecoder().push(packet)?.sendSn)

        val block = QiyiTimerProtocol.decryptBlock(packet.drop(4).map { it.toInt() and 0xFF }.toIntArray())
        block[13] = block[13] xor 0xFF
        val corrupted = packet.take(4).toByteArray() + QiyiTimerProtocol.encryptBlock(block).map { it.toByte() }.toByteArray()
        assertNull(QiyiPacketDecoder().push(corrupted))
    }

    @Test
    fun qiyiDecoder_resyncsAfterAnOutOfOrderPacket() {
        val decoder = QiyiPacketDecoder()
        val twoPacket = QiyiTimerProtocol.encodeMessage(9, 9, QiyiTimerProtocol.CMD_STATE, IntArray(10) { 4 })
        assertEquals(2, twoPacket.size)
        // A stray continuation packet is dropped, and a fresh message still decodes afterwards.
        assertNull(decoder.push(twoPacket[1]))
        val single = QiyiTimerProtocol.encodeMessage(2, 2, QiyiTimerProtocol.CMD_STATE, intArrayOf(4, 4)).single()
        assertEquals(2L, decoder.push(single)?.sendSn)
    }

    @Test
    fun qiyiMacs_fromManufacturerDataAndName() {
        val advertised = byteArrayOf(0x34, 0x12, 0x00, 0x00, 0xA8.toByte(), 0xCC.toByte(), 0x7F)
        assertEquals("CC:A8:00:00:12:34", QiyiTimerProtocol.macFromManufacturerData(advertised))
        assertNull(QiyiTimerProtocol.macFromManufacturerData(byteArrayOf(1, 2, 3)))
        assertEquals("CC:A8:00:00:1A:2B", QiyiTimerProtocol.macFromName("QY-Adapter-1A2B"))
        assertEquals("CC:A1:00:00:00:FF", QiyiTimerProtocol.macFromName("QY-Timer-00FF"))
        assertNull(QiyiTimerProtocol.macFromName("Something"))
        assertNull(QiyiTimerProtocol.parseMac("CC:A8:00:00:12"))
    }

    // --- GAN ------------------------------------------------------------------------------------

    private fun ganPacket(state: Int, minutes: Int = 0, seconds: Int = 0, millis: Int = 0): ByteArray {
        val bytes = intArrayOf(0xFE, 0x08, 0x01, state, minutes, seconds, millis and 0xFF, millis shr 8)
            .map { it.toByte() }.toByteArray()
        val crc = GanTimerProtocol.crc16ccitt(bytes, from = 2)
        return bytes + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    @Test
    fun ganPackets_mapToEvents() {
        assertEquals(SmartTimerEvent.Stopped(62_345L), GanTimerProtocol.parse(ganPacket(4, 1, 2, 345)))
        assertEquals(SmartTimerEvent.GetSet, GanTimerProtocol.parse(ganPacket(1)))
        assertEquals(SmartTimerEvent.HandsOff, GanTimerProtocol.parse(ganPacket(2)))
        assertEquals(SmartTimerEvent.Running, GanTimerProtocol.parse(ganPacket(3)))
        assertEquals(SmartTimerEvent.Idle, GanTimerProtocol.parse(ganPacket(5)))
        assertEquals(SmartTimerEvent.HandsOn, GanTimerProtocol.parse(ganPacket(6)))
        // "finished" always follows "stopped" and the timer's own disconnect notice are ignored.
        assertNull(GanTimerProtocol.parse(ganPacket(7)))
        assertNull(GanTimerProtocol.parse(ganPacket(0)))
    }

    @Test
    fun ganPackets_withBadHeaderOrCrcAreIgnored() {
        val packet = ganPacket(3)
        packet[3] = 4
        assertNull(GanTimerProtocol.parse(packet))
        val badHeader = ganPacket(3).also { it[0] = 0x00 }
        assertNull(GanTimerProtocol.parse(badHeader))
        assertNull(GanTimerProtocol.parse(byteArrayOf()))
    }
}
