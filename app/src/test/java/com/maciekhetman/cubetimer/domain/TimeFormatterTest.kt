package com.maciekhetman.cubetimer.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {

    @Test
    fun formatTime_zeroMillis() {
        assertEquals("0.00", TimeFormatter.formatTime(0L))
    }

    @Test
    fun formatTime_subSecond() {
        assertEquals("0.99", TimeFormatter.formatTime(999L))
    }

    @Test
    fun formatTime_justUnderAMinute() {
        assertEquals("59.99", TimeFormatter.formatTime(59_999L))
    }

    @Test
    fun formatTime_exactlyOneMinute() {
        assertEquals("1:00.00", TimeFormatter.formatTime(60_000L))
    }

    @Test
    fun formatTime_overAnHour() {
        // 1h 00m 00.00s = 3_600_000 ms -> minutes keep accumulating past 60 (no hour segment)
        assertEquals("60:00.00", TimeFormatter.formatTime(3_600_000L))
    }

    @Test
    fun formatTime_overAnHourWithRemainder() {
        // 1h 02m 03.45s
        val millis = 3_600_000L + 2 * 60_000L + 3_000L + 450L
        assertEquals("62:03.45", TimeFormatter.formatTime(millis))
    }

    @Test
    fun splitTimerTime_matchesFormatTimeConcatenation() {
        val samples = listOf(0L, 1L, 999L, 1_000L, 59_999L, 60_000L, 61_230L, 3_600_000L, 3_723_450L)
        for (millis in samples) {
            val (whole, fraction) = TimeFormatter.splitTimerTime(millis)
            assertEquals(
                "splitTimerTime concatenation must equal formatTime for $millis",
                TimeFormatter.formatTime(millis),
                whole + fraction
            )
        }
    }

    @Test
    fun splitTimerTime_zeroMillis() {
        assertEquals("0" to ".00", TimeFormatter.splitTimerTime(0L))
    }

    @Test
    fun splitTimerTime_underAMinuteHasNoColon() {
        val (whole, fraction) = TimeFormatter.splitTimerTime(59_999L)
        assertEquals("59", whole)
        assertEquals(".99", fraction)
    }

    @Test
    fun splitTimerTime_atOrAboveAMinuteHasColon() {
        val (whole, fraction) = TimeFormatter.splitTimerTime(60_000L)
        assertEquals("1:00", whole)
        assertEquals(".00", fraction)
    }

    @Test
    fun formatDuration_seconds() {
        assertEquals("45s", TimeFormatter.formatDuration(45_000L))
    }

    @Test
    fun formatDuration_minutes() {
        assertEquals("2m 5s", TimeFormatter.formatDuration(2 * 60_000L + 5_000L))
    }

    @Test
    fun formatDuration_hours() {
        assertEquals("1h 1m", TimeFormatter.formatDuration(3_600_000L + 60_000L))
    }
}
