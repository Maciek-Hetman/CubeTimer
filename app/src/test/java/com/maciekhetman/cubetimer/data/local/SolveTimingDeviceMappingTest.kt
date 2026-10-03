package com.maciekhetman.cubetimer.data.local

import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.TimingDevice
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** CubeSync's `timing_device` must survive every hop: domain -> Room -> outbox JSON, and back. */
class SolveTimingDeviceMappingTest {

    @Test
    fun externalTimerSolve_roundTripsThroughEntityAndOutboxPayload() {
        val solve = SolveTime(id = "s1", timeInMillis = 9_876L, timingDevice = TimingDevice.EXTERNAL_TIMER)

        val entity = solve.toSolveEntity(ownerId = "user-1")
        assertEquals("external_timer", entity.timingDevice)
        assertEquals(TimingDevice.EXTERNAL_TIMER, entity.toSolveTime().timingDevice)

        val mutation = entity.toUpsertMutation(clientTime = "2026-09-27T10:00:00.000Z")
        val data = NetworkModule.json.parseToJsonElement(mutation.payloadJson!!).jsonObject
        assertEquals("external_timer", data.getValue("timing_device").jsonPrimitive.content)
        assertEquals("external_timer", entity.toSyncPayload().timingDevice)
    }

    @Test
    fun legacyRowsAndPayloadsDefaultToKeyboard() {
        val entity = SolveEntity(id = "old", durationMs = 1_000L, solvedAt = "2026-08-30T08:00:00.000Z")
        assertEquals("keyboard", entity.timingDevice)
        assertEquals(TimingDevice.KEYBOARD, entity.toSolveTime().timingDevice)

        // Solves written by older servers/clients carry no timing_device at all.
        val payload = NetworkModule.json.decodeFromString(
            SolveSyncPayload.serializer(),
            """{"id":"x","duration_ms":1,"penalty":"none","solved_at":"2026-08-30T08:00:00Z","scramble":"","event":"3x3"}"""
        )
        assertEquals("keyboard", payload.timingDevice)
        val snapshot = NetworkModule.json.decodeFromString(
            SolveSnapshotDto.serializer(),
            """{"id":"x","duration_ms":1,"solved_at":"2026-08-30T08:00:00Z","version":2}"""
        )
        assertEquals("keyboard", snapshot.timingDevice)
    }

    @Test
    fun timingDeviceParsing_isLenientAndFallsBackToKeyboard() {
        assertEquals(TimingDevice.EXTERNAL_TIMER, TimingDevice.fromString(" External_Timer "))
        assertEquals(TimingDevice.SMART_CUBE, TimingDevice.fromString("smart_cube"))
        assertEquals(TimingDevice.KEYBOARD, TimingDevice.fromString("stackmat"))
        assertEquals(TimingDevice.KEYBOARD, TimingDevice.fromString(null))
    }
}
