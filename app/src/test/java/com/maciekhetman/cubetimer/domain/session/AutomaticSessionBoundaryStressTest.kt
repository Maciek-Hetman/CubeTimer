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

class AutomaticSessionBoundaryStressTest {

    private val utcZone = ZoneId.of("UTC")
    private val tokyoZone = ZoneId.of("Asia/Tokyo")       // UTC+9
    private val newYorkZone = ZoneId.of("America/New_York") // UTC-5 (or UTC-4 in EDT)
    private val kathmanduZone = ZoneId.of("Asia/Kathmandu") // UTC+5:45

    // =========================================================================
    // 1. HOUR BOUNDARY TESTS (04:59 vs 05:00, 11:59 vs 12:00, 16:59 vs 17:00, 21:59 vs 22:00)
    // =========================================================================

    @Test
    fun testExactHourBoundariesOnDayPartEnum() {
        // Night -> Morning boundary
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(4))
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromHour(5))

        // Morning -> Afternoon boundary
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromHour(11))
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromHour(12))

        // Afternoon -> Evening boundary
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromHour(16))
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromHour(17))

        // Evening -> Night boundary
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromHour(21))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromHour(22))
    }

    @Test
    fun testTimestampSubSecondBoundaryPrecision() {
        // 04:59:59.999 -> NIGHT vs 05:00:00.000 -> MORNING
        val t0459 = LocalDateTime.of(2026, 8, 30, 4, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC)
        val t0500 = LocalDateTime.of(2026, 8, 30, 5, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 night", AutomaticSessionHelper.automaticSessionName(t0459, utcZone))
        assertEquals("30 aug 2026 morning", AutomaticSessionHelper.automaticSessionName(t0500, utcZone))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromInstant(t0459, utcZone))
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromInstant(t0500, utcZone))

        // 11:59:59.999 -> MORNING vs 12:00:00.000 -> AFTERNOON
        val t1159 = LocalDateTime.of(2026, 8, 30, 11, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC)
        val t1200 = LocalDateTime.of(2026, 8, 30, 12, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 morning", AutomaticSessionHelper.automaticSessionName(t1159, utcZone))
        assertEquals("30 aug 2026 afternoon", AutomaticSessionHelper.automaticSessionName(t1200, utcZone))
        assertEquals(DayPart.MORNING, AutomaticSessionHelper.dayPartFromInstant(t1159, utcZone))
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromInstant(t1200, utcZone))

        // 16:59:59.999 -> AFTERNOON vs 17:00:00.000 -> EVENING
        val t1659 = LocalDateTime.of(2026, 8, 30, 16, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC)
        val t1700 = LocalDateTime.of(2026, 8, 30, 17, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 afternoon", AutomaticSessionHelper.automaticSessionName(t1659, utcZone))
        assertEquals("30 aug 2026 evening", AutomaticSessionHelper.automaticSessionName(t1700, utcZone))
        assertEquals(DayPart.AFTERNOON, AutomaticSessionHelper.dayPartFromInstant(t1659, utcZone))
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromInstant(t1700, utcZone))

        // 21:59:59.999 -> EVENING vs 22:00:00.000 -> NIGHT
        val t2159 = LocalDateTime.of(2026, 8, 30, 21, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC)
        val t2200 = LocalDateTime.of(2026, 8, 30, 22, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 evening", AutomaticSessionHelper.automaticSessionName(t2159, utcZone))
        assertEquals("30 aug 2026 night", AutomaticSessionHelper.automaticSessionName(t2200, utcZone))
        assertEquals(DayPart.EVENING, AutomaticSessionHelper.dayPartFromInstant(t2159, utcZone))
        assertEquals(DayPart.NIGHT, AutomaticSessionHelper.dayPartFromInstant(t2200, utcZone))

        // 23:59:59.999 -> NIGHT vs 00:00:00.000 -> NIGHT (next day)
        val t2359 = LocalDateTime.of(2026, 8, 30, 23, 59, 59, 999_000_000).toInstant(ZoneOffset.UTC)
        val t0000 = LocalDateTime.of(2026, 8, 31, 0, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 night", AutomaticSessionHelper.automaticSessionName(t2359, utcZone))
        assertEquals("31 aug 2026 night", AutomaticSessionHelper.automaticSessionName(t0000, utcZone))
    }

    @Test
    fun testCalendarBoundariesMonthAndYearTransitions() {
        // Month end transition
        val feb28 = LocalDateTime.of(2026, 2, 28, 23, 59, 59).toInstant(ZoneOffset.UTC)
        val mar01 = LocalDateTime.of(2026, 3, 1, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("28 feb 2026 night", AutomaticSessionHelper.automaticSessionName(feb28, utcZone))
        assertEquals("1 mar 2026 night", AutomaticSessionHelper.automaticSessionName(mar01, utcZone))

        // Leap day
        val leapDay = LocalDateTime.of(2024, 2, 29, 14, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("29 feb 2024 afternoon", AutomaticSessionHelper.automaticSessionName(leapDay, utcZone))

        // Year end transition
        val dec31 = LocalDateTime.of(2026, 12, 31, 23, 59, 59).toInstant(ZoneOffset.UTC)
        val jan01 = LocalDateTime.of(2027, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC)
        assertEquals("31 dec 2026 night", AutomaticSessionHelper.automaticSessionName(dec31, utcZone))
        assertEquals("1 jan 2027 night", AutomaticSessionHelper.automaticSessionName(jan01, utcZone))
    }

    @Test
    fun testTimezoneOffsetsOnSessionNaming() {
        // 2026-08-30 03:30:00 UTC -> in Tokyo (UTC+9) it is 12:30 PM (afternoon)
        val instant = LocalDateTime.of(2026, 8, 30, 3, 30, 0).toInstant(ZoneOffset.UTC)
        assertEquals("30 aug 2026 night", AutomaticSessionHelper.automaticSessionName(instant, utcZone))
        assertEquals("30 aug 2026 afternoon", AutomaticSessionHelper.automaticSessionName(instant, tokyoZone))

        // In Kathmandu (UTC+5:45), 03:30 UTC -> 09:15 AM (morning)
        assertEquals("30 aug 2026 morning", AutomaticSessionHelper.automaticSessionName(instant, kathmanduZone))
    }

    // =========================================================================
    // 2. INACTIVITY GAP BOUNDARY TESTS (60 MINS VS 60 MINS + 1 MS)
    // =========================================================================

    @Test
    fun testInactivityGapExactMillisecondBoundaries() {
        val startIso = "2026-08-30T10:00:00Z"
        val startMs = Instant.parse(startIso).toEpochMilli()
        val gapMs = AutomaticSessionHelper.DEFAULT_INACTIVITY_GAP_MILLIS // 3,600,000 ms

        val session = Session(
            id = "sess-gap-test",
            ownerId = "user-1",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = startIso
        )

        // 1. Exactly 0 ms elapsed -> TRUE
        assertTrue(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = startMs,
                nowMs = startMs,
                gapMs = gapMs
            )
        )

        // 2. Exactly gapMs - 1 ms (59 min 59 sec 999 ms) -> TRUE
        assertTrue(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = startMs,
                nowMs = startMs + gapMs - 1L,
                gapMs = gapMs
            )
        )

        // 3. Exactly gapMs (60 min 0 ms) -> TRUE
        assertTrue(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = startMs,
                nowMs = startMs + gapMs,
                gapMs = gapMs
            )
        )

        // 4. Exactly gapMs + 1 ms (60 min 0 sec 1 ms) -> FALSE
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = startMs,
                nowMs = startMs + gapMs + 1L,
                gapMs = gapMs
            )
        )

        // 5. Gap exceeded by 10 minutes -> FALSE
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = startMs,
                nowMs = startMs + gapMs + 600_000L,
                gapMs = gapMs
            )
        )

        // 6. Negative elapsed time (device clock jumped backwards) -> FALSE
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = session,
                lastSolveTimestampMs = startMs,
                nowMs = startMs - 1000L,
                gapMs = gapMs
            )
        )
    }

    @Test
    fun testInactivityGapWithEntityAndSolveOverloadBoundaries() {
        val sessionEntity = SessionEntity(
            id = "entity-gap",
            ownerId = "user-1",
            name = "30 aug 2026 afternoon",
            event = "3x3",
            kind = "automatic",
            startedAt = "2026-08-30T14:00:00Z"
        )
        val solveEntity = SolveEntity(
            id = "solve-gap",
            ownerId = "user-1",
            sessionId = "entity-gap",
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T14:30:00Z"
        )

        val solveMs = Instant.parse("2026-08-30T14:30:00Z").toEpochMilli()
        val gapMs = 3_600_000L

        // Exactly 60 mins after solve -> TRUE
        assertTrue(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = sessionEntity,
                lastSolve = solveEntity,
                nowMs = solveMs + gapMs,
                gapMs = gapMs,
                event = "3x3"
            )
        )

        // 60 mins + 1 ms after solve -> FALSE
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = sessionEntity,
                lastSolve = solveEntity,
                nowMs = solveMs + gapMs + 1L,
                gapMs = gapMs,
                event = "3x3"
            )
        )

        // Wrong event -> FALSE
        assertFalse(
            AutomaticSessionHelper.shouldReuseAutomaticSession(
                session = sessionEntity,
                lastSolve = solveEntity,
                nowMs = solveMs + 1000L,
                gapMs = gapMs,
                event = "4x4"
            )
        )
    }

    // =========================================================================
    // 3. DISAMBIGUATION SUFFIXING TESTS
    // =========================================================================

    @Test
    fun testDisambiguationSuffixSequencing() {
        val base = "30 aug 2026 morning"

        // 0 collisions
        assertEquals(base, AutomaticSessionHelper.disambiguateSessionName(base, emptyList()))

        // 1 collision
        assertEquals("$base 2", AutomaticSessionHelper.disambiguateSessionName(base, listOf(base)))

        // 2 collisions
        assertEquals("$base 3", AutomaticSessionHelper.disambiguateSessionName(base, listOf(base, "$base 2")))

        // 3 collisions
        assertEquals("$base 4", AutomaticSessionHelper.disambiguateSessionName(base, listOf(base, "$base 2", "$base 3")))

        // Gap filling: missing " 2"
        assertEquals("$base 2", AutomaticSessionHelper.disambiguateSessionName(base, listOf(base, "$base 3", "$base 4")))

        // Base name free, only numbered suffixes exist -> should return base
        assertEquals(base, AutomaticSessionHelper.disambiguateSessionName(base, listOf("$base 2", "$base 3")))
    }

    @Test
    fun testDisambiguationCaseInsensitivityAndWhitespaceTrimming() {
        val base = "30 aug 2026 morning"

        // Uppercase existing name
        val listUpper = listOf("30 AUG 2026 MORNING")
        assertEquals("$base 2", AutomaticSessionHelper.disambiguateSessionName(base, listUpper))

        // Existing name with extra whitespace
        val listWhitespace = listOf("  30 aug 2026 morning  ", " 30 aug 2026 morning 2 ")
        assertEquals("$base 3", AutomaticSessionHelper.disambiguateSessionName(base, listWhitespace))
    }

    @Test
    fun testDisambiguationLargeScaleDuplicateResolution() {
        val base = "30 aug 2026 evening"
        val existing = mutableListOf(base)
        for (i in 2..100) {
            existing.add("$base $i")
        }

        // With 1..100 taken, next must be 101
        val result = AutomaticSessionHelper.disambiguateSessionName(base, existing)
        assertEquals("$base 101", result)
    }
}
