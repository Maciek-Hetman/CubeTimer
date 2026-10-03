package com.maciekhetman.cubetimer.viewmodel

import android.text.format.DateFormat
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Robolectric: the date line comes from android.text.format.DateFormat (the app's date and time format).
@RunWith(RobolectricTestRunner::class)
class ConflictUiMapperTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private lateinit var previousLocale: Locale

    @Before
    fun setup() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    private fun conflict(type: String, local: String?, server: String?) = ConflictEntity(
        conflictId = "c1",
        ownerId = "usr_1",
        mutationId = "m1",
        entityType = type,
        entityId = "e1",
        localPayloadJson = local,
        serverPayloadJson = server,
        createdAt = "2026-08-30T10:05:00.000Z"
    )

    /** The date line for [iso] in [zone]: the app's short date and time, spelled out here from its skeleton. */
    private fun expectedDate(iso: String, zone: TimeZone = utc): String {
        val locale = Locale.getDefault()
        val format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMdjmm"), locale)
        format.timeZone = zone
        return format.format(Date(Instant.parse(iso).toEpochMilli()))
    }

    @Test
    fun solveConflictShowsEventTimePenaltyAndDate() {
        val ui = ConflictUiMapper.map(
            conflict(
                "solve",
                local = """{"id":"e1","duration_ms":12340,"penalty":"plus_two","solved_at":"2026-08-30T10:00:00.000Z","scramble":"R","event":"megaminx","timing_device":"smart_cube"}""",
                server = """{"id":"e1","duration_ms":61500,"penalty":"none","solved_at":"2026-08-30T10:00:00Z","event":"megaminx","version":2}"""
            ),
            timeZone = utc
        )
        assertEquals("Solve", ui.title)
        assertEquals(listOf("Megaminx · 14.34 (+2)", expectedDate("2026-08-30T10:00:00Z")), ui.local.lines)
        assertEquals(listOf("Megaminx · 1:01.50", expectedDate("2026-08-30T10:00:00Z")), ui.server.lines)
        assertFalse(ui.local.deleted)
        assertFalse(ui.server.deleted)
    }

    @Test
    fun solveDateIsShownInTheGivenTimeZone() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val ui = ConflictUiMapper.map(
            conflict(
                "solve",
                local = """{"id":"e1","duration_ms":12340,"penalty":"none","solved_at":"2026-08-30T10:00:00.000Z","scramble":"R","event":"3x3"}""",
                server = null
            ),
            timeZone = tokyo
        )
        val date = ui.local.lines.last()
        assertEquals(expectedDate("2026-08-30T10:00:00Z", tokyo), date)
        assertTrue(date != expectedDate("2026-08-30T10:00:00Z", utc))
    }

    @Test
    fun solveWithoutAUsableDateShowsNoDateLine() {
        val ui = ConflictUiMapper.map(
            conflict(
                "solve",
                local = """{"id":"e1","duration_ms":12340,"penalty":"none","solved_at":"not a date","scramble":"R","event":"3x3"}""",
                server = """{"id":"e1","duration_ms":12340,"penalty":"none","solved_at":"1970-01-01T00:00:00Z","event":"3x3","version":2}"""
            ),
            timeZone = utc
        )
        assertEquals(listOf("3x3 · 12.34"), ui.local.lines)
        assertEquals(listOf("3x3 · 12.34"), ui.server.lines)
    }

    @Test
    fun nullLocalPayloadAndServerDeletedAtShowDeleted() {
        val ui = ConflictUiMapper.map(
            conflict(
                "solve",
                local = null,
                server = """{"id":"e1","duration_ms":1000,"solved_at":"2026-08-30T10:00:00Z","deleted_at":"2026-08-31T10:00:00Z"}"""
            ),
            timeZone = utc
        )
        assertTrue(ui.local.deleted)
        assertEquals(listOf(ConflictUiMapper.DELETED_LINE), ui.local.lines)
        assertTrue(ui.server.deleted)
        assertEquals(listOf(ConflictUiMapper.DELETED_LINE), ui.server.lines)
    }

    @Test
    fun sessionConflictShowsNameAndArchivedState() {
        val ui = ConflictUiMapper.map(
            conflict(
                "session",
                local = """{"id":"e1","name":"PB grind","event":"3x3","kind":"manual","started_at":"2026-08-30T10:00:00Z","archived":true}""",
                server = """{"id":"e1","name":"Practice","event":"3x3","kind":"manual","started_at":"2026-08-30T10:00:00Z","archived":false,"version":4}"""
            ),
            timeZone = utc
        )
        assertEquals("Session", ui.title)
        assertEquals(listOf("PB grind", "3x3 · Archived"), ui.local.lines)
        assertEquals(listOf("Practice", "3x3 · Active"), ui.server.lines)
    }

    @Test
    fun undecodableJsonFallsBackToGenericLine() {
        val ui = ConflictUiMapper.map(
            conflict("session", local = "{not json", server = """{"unexpected":true}"""),
            timeZone = utc
        )
        assertEquals(listOf(ConflictUiMapper.UNREADABLE_LINE), ui.local.lines)
        assertEquals(listOf(ConflictUiMapper.UNREADABLE_LINE), ui.server.lines)
        assertFalse(ui.local.deleted)
    }

    @Test
    fun dnfShowsRawTimeInParentheses() {
        assertEquals("DNF (9.87)", ConflictUiMapper.formatSolveTime(9870, "dnf"))
        assertEquals("9.87", ConflictUiMapper.formatSolveTime(9870, "none"))
    }
}
