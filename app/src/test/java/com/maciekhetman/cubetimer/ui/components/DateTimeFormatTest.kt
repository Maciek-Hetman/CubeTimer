package com.maciekhetman.cubetimer.ui.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
class DateTimeFormatTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val instant = Instant.parse("2026-10-03T10:00:00Z").toEpochMilli()

    private lateinit var previousLocale: Locale
    private lateinit var previousZone: TimeZone

    @Before
    fun setup() {
        previousLocale = Locale.getDefault()
        previousZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun showsYearMonthDayAndTimeInTheGivenTimeZone() {
        // German uses a 24-hour clock, so the hour reads the same on every device.
        Locale.setDefault(Locale.GERMANY)

        val inUtc = formatDateTime(instant, utc)
        val inTokyo = formatDateTime(instant, tokyo)

        assertTrue(inUtc, "2026" in inUtc && "10:00" in inUtc)
        assertTrue(inTokyo, "2026" in inTokyo && "19:00" in inTokyo)
    }

    @Test
    fun usesTheDeviceTimeZoneByDefault() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(tokyo)

        assertEquals(formatDateTime(instant, tokyo), formatDateTime(instant))
    }

    @Test
    fun followsTheLanguageAtTheTimeOfEachCall() {
        Locale.setDefault(Locale.US)
        val english = formatDateTime(instant, utc)
        Locale.setDefault(Locale.GERMANY)
        val german = formatDateTime(instant, utc)
        Locale.setDefault(Locale.US)
        val englishAgain = formatDateTime(instant, utc)

        assertTrue(english, "Oct" in english)
        assertTrue(german, "Okt" in german)
        assertNotEquals(english, german)
        assertEquals(english, englishAgain)
    }
}
