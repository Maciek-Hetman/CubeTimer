package com.maciekhetman.cubetimer.domain.session

import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.model.DayPart
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AutomaticSessionHelperTest {

    private val utcZone = ZoneId.of("UTC")

    @Test
    fun testDayPartBoundaries() {
        // Morning: 05:00:00 to 11:59:59 (hours 5..11)
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromHour(5))
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromHour(8))
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromHour(11))

        // Afternoon: 12:00:00 to 16:59:59 (hours 12..16)
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromHour(12))
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromHour(14))
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromHour(16))

        // Evening: 17:00:00 to 21:59:59 (hours 17..21)
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromHour(17))
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromHour(19))
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromHour(21))

        // Night: 22:00:00 to 04:59:59 (hours 22..23 and 0..4)
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(22))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(23))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(0))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(3))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(4))
    }

    @Test
    fun testAutomaticSessionNameFormat() {
        // 2026-08-30 at 09:30 UTC -> "30 aug 2026 morning"
        val instantMorning = LocalDateTime.of(2026, 8, 30, 9, 30).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 morning", AutomaticSessionHelper.automaticSessionName(instantMorning, utcZone))

        // 2026-08-30 at 14:15 UTC -> "30 aug 2026 afternoon"
        val instantAfternoon = LocalDateTime.of(2026, 8, 30, 14, 15).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 afternoon", AutomaticSessionHelper.automaticSessionName(instantAfternoon, utcZone))

        // 2026-08-30 at 19:45 UTC -> "30 aug 2026 evening"
        val instantEvening = LocalDateTime.of(2026, 8, 30, 19, 45).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 evening", AutomaticSessionHelper.automaticSessionName(instantEvening, utcZone))

        // 2026-08-30 at 23:10 UTC -> "30 aug 2026 night"
        val instantNight = LocalDateTime.of(2026, 8, 30, 23, 10).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 night", AutomaticSessionHelper.automaticSessionName(instantNight, utcZone))

        // 2026-09-01 at 02:00 UTC -> single-digit day "1 sep 2026 night" (no leading zero)
        val instantSepFirst = LocalDateTime.of(2026, 9, 1, 2, 0).toInstant(ZoneOffset.UTC)
        assertEquals("1 sep 2026 night", AutomaticSessionHelper.automaticSessionName(instantSepFirst, utcZone))
    }

    @Test
    fun testUniqueSessionNameDisambiguation() {
        val instant = LocalDateTime.of(2026, 8, 30, 10, 0).toInstant(ZoneOffset.UTC)
        val base = "30 aug 2026 morning"

        // No collisions -> returns base
        val name1 = AutomaticSessionHelper.uniqueAutomaticSessionName(instant, emptyList(), utcZone)
        assertEquals(base, name1)

        // Single collision -> returns base + " 2"
        val name2 = AutomaticSessionHelper.uniqueAutomaticSessionName(instant, listOf(base), utcZone)
        assertEquals("$base 2", name2)

        // Multiple collisions -> returns base + " 3"
        val name3 = AutomaticSessionHelper.uniqueAutomaticSessionName(instant, listOf(base, "$base 2"), utcZone)
        assertEquals("$base 3", name3)

        // Gapped collisions -> fills next available suffix
        val name4 = AutomaticSessionHelper.uniqueAutomaticSessionName(instant, listOf(base, "$base 2", "$base 3"), utcZone)
        assertEquals("$base 4", name4)
    }

    @Test
    fun testShouldReuseAutomaticSessionWithinGap() {
        val baseInstant = Instant.parse("2026-08-30T10:00:00Z")
        val baseMs = baseInstant.toEpochMilli()

        val session = Session(
            id = "auto-1",
            ownerId = "user-1",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = baseInstant.toString()
        )

        // Solve 15 minutes after session start
        val now15m = baseMs + 15 * 60 * 1000L
        assertTrue(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = null,
                nowMs = now15m,
                mode = Mode.CUBE_3x3
            )
        )

        // Solve 59 minutes after last solve
        val lastSolveMs = baseMs + 15 * 60 * 1000L
        val now59m = lastSolveMs + 59 * 60 * 1000L
        assertTrue(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = lastSolveMs,
                nowMs = now59m,
                mode = Mode.CUBE_3x3
            )
        )
    }

    @Test
    fun testShouldNotReuseAutomaticSessionExceededGap() {
        val baseInstant = Instant.parse("2026-08-30T10:00:00Z")
        val baseMs = baseInstant.toEpochMilli()

        val session = Session(
            id = "auto-1",
            ownerId = "user-1",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = baseInstant.toString()
        )

        // Solve 61 minutes after session start without solves -> false
        val now61m = baseMs + 61 * 60 * 1000L
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = null,
                nowMs = now61m,
                mode = Mode.CUBE_3x3
            )
        )

        // Solve 61 minutes after last solve -> false
        val lastSolveMs = baseMs + 10 * 60 * 1000L
        val nowSolve61m = lastSolveMs + 61 * 60 * 1000L
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = lastSolveMs,
                nowMs = nowSolve61m,
                mode = Mode.CUBE_3x3
            )
        )
    }

    @Test
    fun testShouldNotReuseClosedArchivedOrDeletedSession() {
        val baseInstant = Instant.parse("2026-08-30T10:00:00Z")
        val nowMs = baseInstant.toEpochMilli() + 5000L

        val closedSession = Session(
            id = "auto-closed",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = baseInstant.toString(),
            endedAt = "2026-08-30T10:05:00Z"
        )
        assertFalse(AutomaticSessionHelper.shouldReuseAutomaticSession(closedSession, null, nowMs))

        val archivedSession = Session(
            id = "auto-archived",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = baseInstant.toString(),
            archived = true
        )
        assertFalse(AutomaticSessionHelper.shouldReuseAutomaticSession(archivedSession, null, nowMs))

        val deletedSession = Session(
            id = "auto-deleted",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = baseInstant.toString(),
            deletedAt = "2026-08-30T10:06:00Z"
        )
        assertFalse(AutomaticSessionHelper.shouldReuseAutomaticSession(deletedSession, null, nowMs))

        val manualSession = Session(
            id = "manual-1",
            name = "Custom",
            event = Mode.CUBE_3x3,
            kind = SessionKind.MANUAL,
            startedAt = baseInstant.toString()
        )
        assertFalse(AutomaticSessionHelper.shouldReuseAutomaticSession(manualSession, null, nowMs))

        val differentModeSession = Session(
            id = "auto-diff-mode",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_4x4,
            kind = SessionKind.AUTOMATIC,
            startedAt = baseInstant.toString()
        )
        assertFalse(AutomaticSessionHelper.shouldReuseAutomaticSession(differentModeSession, null, nowMs, mode = Mode.CUBE_3x3))
    }

    @Test
    fun testEntityOverloadForReuseCheck() {
        val baseIso = "2026-08-30T10:00:00Z"
        val baseMs = Instant.parse(baseIso).toEpochMilli()

        val entity = SessionEntity(
            id = "entity-1",
            ownerId = "user-1",
            name = "30 aug 2026 morning",
            event = "3x3",
            kind = "automatic",
            startedAt = baseIso
        )

        val solveEntity = SolveEntity(
            id = "solve-1",
            ownerId = "user-1",
            sessionId = "entity-1",
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:15:00Z"
        )

        // 20 min after solve -> true
        val now20m = Instant.parse("2026-08-30T10:35:00Z").toEpochMilli()
        assertTrue(AutomaticSessionHelper.shouldReuseAutomaticSession(entity, solveEntity, now20m, event = "3x3"))

        // 65 min after solve -> false
        val now65m = Instant.parse("2026-08-30T11:20:00Z").toEpochMilli()
        assertFalse(AutomaticSessionHelper.shouldReuseAutomaticSession(entity, solveEntity, now65m, event = "3x3"))
    }
}
