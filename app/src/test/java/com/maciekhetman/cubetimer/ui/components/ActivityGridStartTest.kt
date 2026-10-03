package com.maciekhetman.cubetimer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class ActivityGridStartTest {

    private val weeks = 12

    @Test
    fun midWeekStartsOnTheSundayElevenWeeksBack() {
        // Thursday 2026-10-01 is in the week that began on Sunday 2026-09-27
        assertEquals(LocalDate.of(2026, 7, 12), activityGridStart(LocalDate.of(2026, 10, 1), weeks))
    }

    @Test
    fun sundayTodayStartsElevenWeeksBeforeThatSunday() {
        val sunday = LocalDate.of(2026, 9, 27)

        assertEquals(sunday.minusWeeks(11), activityGridStart(sunday, weeks))
    }

    @Test
    fun saturdayTodayBelongsToTheWeekThatBeganOnTheSundayBefore() {
        val saturday = LocalDate.of(2026, 10, 3)

        assertEquals(LocalDate.of(2026, 7, 12), activityGridStart(saturday, weeks))
        // The next day opens a new grid week, so the whole grid moves on by one
        assertEquals(LocalDate.of(2026, 7, 19), activityGridStart(saturday.plusDays(1), weeks))
    }

    @Test
    fun gridAlwaysStartsOnASundayAndEndsInTheWeekOfToday() {
        var today = LocalDate.of(2026, 1, 1)
        repeat(400) {
            val start = activityGridStart(today, weeks)
            val lastCell = start.plusDays(weeks * 7L - 1)

            assertEquals("grid for $today must start on a Sunday", DayOfWeek.SUNDAY, start.dayOfWeek)
            assertFalse("$today must not be after the grid's last cell", today.isAfter(lastCell))
            assertTrue("$today must be in the grid's last week", today.isAfter(lastCell.minusDays(7)))
            today = today.plusDays(1)
        }
    }

    @Test
    fun startDoesNotDependOnTheDefaultLocale() {
        val original = Locale.getDefault()
        try {
            val today = LocalDate.of(2026, 10, 1)
            // Sunday-first (US) and Monday-first (Poland, UK) locales must all agree
            for (tag in listOf("en-US", "pl-PL", "en-GB")) {
                Locale.setDefault(Locale.forLanguageTag(tag))

                assertEquals(tag, LocalDate.of(2026, 7, 12), activityGridStart(today, weeks))
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
