package com.maciekhetman.cubetimer.data.bluetooth

import com.maciekhetman.cubetimer.domain.bluetooth.GanTimerProtocol
import com.maciekhetman.cubetimer.domain.bluetooth.QiyiPacketDecoder
import com.maciekhetman.cubetimer.domain.bluetooth.QiyiTimerProtocol
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SmartTimerDriverTest {

    private class RecordingWriter : TimerLinkWriter {
        val packets = mutableListOf<ByteArray>()
        override suspend fun write(packet: ByteArray) {
            packets += packet
        }

        fun decodeAll(): List<QiyiTimerProtocol.Message> {
            val decoder = QiyiPacketDecoder()
            return packets.mapNotNull { decoder.push(it) }
        }
    }

    @Test
    fun qiyiDriver_sendsHelloWithItsMacOnConnect() = runTest {
        val writer = RecordingWriter()
        QiyiTimerDriver("cc:a8:00:00:12:34").onConnected(writer)

        val hello = writer.decodeAll().single()
        assertEquals(QiyiTimerProtocol.CMD_HELLO, hello.cmd)
        assertEquals(1L, hello.sendSn)
        assertEquals(listOf(0x34, 0x12, 0x00, 0x00, 0xA8, 0xCC), hello.payload.takeLast(6))
    }

    @Test
    fun qiyiDriver_acknowledgesRecordedSolvesButNotStatusUpdates() = runTest {
        val driver = QiyiTimerDriver("CC:A8:00:00:12:34")
        val writer = RecordingWriter()

        val status = QiyiTimerProtocol.encodeMessage(5, 1, QiyiTimerProtocol.CMD_STATE, intArrayOf(4, 4, 0, 5, 3, 0, 0, 0, 0))
        val statusEvents = status.map { driver.onNotification(it, writer) }
        assertEquals(SmartTimerEvent.Running, statusEvents.last())
        assertTrue("status updates are not acknowledged", writer.packets.isEmpty())

        val record = QiyiTimerProtocol.encodeMessage(6, 1, QiyiTimerProtocol.CMD_STATE, intArrayOf(1, 1, 0, 12, 0, 0, 0, 0, 0, 0, 0x2B, 0x67))
        val recordEvents = record.map { driver.onNotification(it, writer) }
        assertEquals(SmartTimerEvent.Stopped(11_111L), recordEvents.last())

        val ack = writer.decodeAll().single()
        assertEquals(QiyiTimerProtocol.CMD_STATE, ack.cmd)
        assertEquals(2L, ack.sendSn)
        assertEquals(6L, ack.ackSn)
        assertArrayEquals(intArrayOf(0), ack.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun qiyiDriver_rejectsAnInvalidMac() {
        QiyiTimerDriver("not-a-mac")
    }

    @Test
    fun ganDriver_decodesStateNotificationsAndNeverWrites() = runTest {
        val driver = GanTimerDriver()
        val writer = RecordingWriter()
        val bytes = byteArrayOf(0xFE.toByte(), 0x08, 0x01, 0x03, 0, 0, 0, 0)
        val crc = GanTimerProtocol.crc16ccitt(bytes, from = 2)
        val packet = bytes + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())

        driver.onConnected(writer)
        assertEquals(SmartTimerEvent.Running, driver.onNotification(packet, writer))
        assertTrue(writer.packets.isEmpty())
        assertNull(driver.writeCharacteristicUuid)
    }

    @Test
    fun detector_identifiesTimersByNameServiceAndManufacturerData() {
        val none = emptyList<UUID>()
        assertEquals(SmartTimerModel.QIYI, SmartTimerDetector.detect("QY-Timer-1A2B", none, hasQiyiManufacturerData = false))
        assertEquals(SmartTimerModel.QIYI, SmartTimerDetector.detect("QY-Adapter-00FF", none, hasQiyiManufacturerData = false))
        assertEquals(SmartTimerModel.GAN, SmartTimerDetector.detect("GAN-a1b2", none, hasQiyiManufacturerData = false))
        assertEquals(SmartTimerModel.GAN, SmartTimerDetector.detect("GAN Halo", none, hasQiyiManufacturerData = false))
        assertEquals(
            SmartTimerModel.QIYI,
            SmartTimerDetector.detect(null, listOf(QiyiTimerProtocol.SERVICE_UUID), hasQiyiManufacturerData = true)
        )
        // The generic 0xFFF0 service alone only counts for nameless devices.
        assertEquals(SmartTimerModel.GAN, SmartTimerDetector.detect("", listOf(GanTimerProtocol.SERVICE_UUID), hasQiyiManufacturerData = false))
        assertNull(SmartTimerDetector.detect("Mi Band 7", listOf(GanTimerProtocol.SERVICE_UUID), hasQiyiManufacturerData = false))
        assertNull(SmartTimerDetector.detect("Headphones", none, hasQiyiManufacturerData = false))
    }

    @Test
    fun qiyiMac_prefersAdvertisedThenDeviceAddressThenName() {
        val advertised = byteArrayOf(0x34, 0x12, 0x00, 0x00, 0xA8.toByte(), 0xCC.toByte())
        assertEquals("CC:A8:00:00:12:34", SmartTimerDetector.qiyiMac(advertised, "11:22:33:44:55:66", "QY-Timer-1A2B"))
        assertEquals("11:22:33:44:55:AA", SmartTimerDetector.qiyiMac(null, "11:22:33:44:55:aa", "QY-Timer-1A2B"))
        assertEquals("CC:A1:00:00:1A:2B", SmartTimerDetector.qiyiMac(null, null, "QY-Timer-1A2B"))
        assertNull(SmartTimerDetector.qiyiMac(null, "garbage", "Unknown"))
    }
}
