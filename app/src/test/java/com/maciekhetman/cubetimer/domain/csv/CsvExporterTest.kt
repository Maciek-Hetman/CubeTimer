package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.TimingDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets

class CsvExporterTest {

    @Test
    fun exportSolves_emptyList_writesOnlyCommentAndHeader() {
        val output = CsvExporter.exportSolvesToString(emptyList()) { "Session" }
        val lines = output.split("\r\n")

        assertEquals(3, lines.size) // Line 1, Line 2, and trailing empty from split
        assertEquals("# Source: CubeTimer", lines[0])
        assertEquals("solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble,timing_device", lines[1])
        assertEquals("", lines[2])
    }

    @Test
    fun exportSolves_singleSolve_formatsAllColumnsAccurately() {
        val solve = SolveTime(
            id = "solve-123",
            timeInMillis = 14250L,
            penalty = Penalty.NONE,
            timestamp = 1700000000000L,
            scramble = "R U R' U' R' F R2 U' R' U' R U R' F'",
            mode = Mode.CUBE_3x3,
            sessionId = "session-456"
        )

        val output = CsvExporter.exportSolvesToString(listOf(solve)) { id ->
            if (id == "session-456") "Practice Session" else "Default"
        }

        val lines = output.split("\r\n")
        assertEquals("# Source: CubeTimer", lines[0])
        assertEquals(CsvFormat.HEADER_LINE, lines[1])
        assertEquals(
            "solve-123,session-456,Practice Session,3x3,1700000000000,14250,none,R U R' U' R' F R2 U' R' U' R U R' F',keyboard",
            lines[2]
        )
    }

    @Test
    fun exportSolves_penaltyVariants_exportsPlusTwoAndDnf() {
        val solves = listOf(
            SolveTime(id = "s1", timeInMillis = 10000L, penalty = Penalty.NONE),
            SolveTime(id = "s2", timeInMillis = 11000L, penalty = Penalty.PLUS_TWO),
            SolveTime(id = "s3", timeInMillis = 12000L, penalty = Penalty.DNF)
        )

        val output = CsvExporter.exportSolvesToString(solves) { "Session" }
        val lines = output.split("\r\n")

        assertTrue(lines[2].contains(",10000,none,"))
        assertTrue(lines[3].contains(",11000,+2,"))
        assertTrue(lines[4].contains(",12000,dnf,"))
    }

    @Test
    fun exportSolves_rawDurationExported_doesNotUseDisplayTime() {
        // Solve with 10_000ms duration and PLUS_TWO has displayTime = 12_000ms
        val solve = SolveTime(
            id = "s-raw",
            timeInMillis = 10000L,
            penalty = Penalty.PLUS_TWO
        )
        assertEquals(12000L, solve.displayTime)

        val output = CsvExporter.exportSolvesToString(listOf(solve)) { "Session" }
        val lines = output.split("\r\n")

        // Column 6 (time) MUST be 10000, NOT 12000
        val rowCols = lines[2].split(",")
        assertEquals("10000", rowCols[5])
        assertEquals("+2", rowCols[6])
    }

    @Test
    fun exportSolves_nullSessionId_exportsEmptyStringAndInvokesLookupWithNull() {
        val solve = SolveTime(
            id = "s-null-session",
            timeInMillis = 9500L,
            sessionId = null
        )

        var lookupReceivedArg: String? = "not-called"
        val output = CsvExporter.exportSolvesToString(listOf(solve)) { id ->
            lookupReceivedArg = id
            "Unassigned Solves"
        }

        assertEquals(null, lookupReceivedArg)
        val lines = output.split("\r\n")
        val row = lines[2]

        // Format: solve_id,,session_name,...
        assertTrue(row.startsWith("s-null-session,,Unassigned Solves,"))
    }

    @Test
    fun exportSolves_scrambleAndSessionNameWithSpecialCharacters_escapedProperly() {
        val solve = SolveTime(
            id = "s-special",
            timeInMillis = 15000L,
            scramble = "[R, U] \"Megaminx\" \n D++",
            sessionId = "sess-1"
        )

        val output = CsvExporter.exportSolvesToString(listOf(solve)) { "3x3, \"Advanced\"" }

        // Must escape session_name as "3x3, ""Advanced"""
        // Must escape scramble as "[R, U] ""Megaminx"" \n D++"
        assertTrue(output.contains("\"3x3, \"\"Advanced\"\"\""))
        assertTrue(output.contains("\"[R, U] \"\"Megaminx\"\" \n D++\""))
    }

    @Test
    fun exportSolves_allPuzzleModes_mapsToCanonicalNames() {
        val modes = listOf(
            Mode.CUBE_2x2 to "2x2",
            Mode.CUBE_3x3 to "3x3",
            Mode.CUBE_4x4 to "4x4",
            Mode.CUBE_5x5 to "5x5",
            Mode.MEGAMINX to "megaminx",
            Mode.PYRAMINX to "pyraminx"
        )

        val solves = modes.mapIndexed { idx, (mode, _) ->
            SolveTime(id = "solve-$idx", timeInMillis = 10000L, mode = mode)
        }

        val output = CsvExporter.exportSolvesToString(solves) { "Session" }
        val lines = output.split("\r\n").filter { it.isNotBlank() }

        modes.forEachIndexed { idx, (_, expectedEventStr) ->
            val row = lines[idx + 2] // Skip Line 1 and Header
            val cols = row.split(",")
            assertEquals(expectedEventStr, cols[3])
        }
    }

    @Test
    fun exportSolves_largeBatch_streamsEfficientlyWithoutTrunctation() {
        val count = 1000
        val solves = (1..count).map { idx ->
            SolveTime(
                id = "solve-$idx",
                timeInMillis = (10000L + idx),
                penalty = if (idx % 10 == 0) Penalty.PLUS_TWO else Penalty.NONE,
                timestamp = 1700000000000L + idx,
                scramble = "R U R' U'",
                sessionId = "sess-${idx % 5}"
            )
        }

        val stream = ByteArrayOutputStream()
        CsvExporter.exportSolves(stream, solves) { id -> "Session $id" }

        val resultStr = stream.toString(StandardCharsets.UTF_8.name())
        val lines = resultStr.split("\r\n")

        // 1 magic line + 1 header + 1000 rows + 1 empty trailing = 1003 tokens
        assertEquals(count + 3, lines.size)
        assertEquals("# Source: CubeTimer", lines[0])
        assertEquals(CsvFormat.HEADER_LINE, lines[1])
        assertTrue(lines[count + 1].startsWith("solve-1000,"))
    }

    @Test
    fun exportSolves_utf8NonAscii_preservesExactCharacters() {
        val solve = SolveTime(
            id = "solve-unicode",
            timeInMillis = 12000L,
            scramble = "R U R' // 日本語 🏆",
            sessionId = "sess-pl"
        )

        val output = CsvExporter.exportSolvesToString(listOf(solve)) { "Sesja Główna 🇵🇱" }
        assertTrue(output.contains("Sesja Główna 🇵🇱"))
        assertTrue(output.contains("R U R' // 日本語 🏆"))
    }

    private fun parseRows(csv: String): List<List<String>> {
        val reader = CsvRecordReader(StringReader(csv))
        return generateSequence { reader.readNextRecord() }.toList()
    }

    @Test
    fun exportSolves_timingDevice_isExportedAsLastColumn() {
        val solves = listOf(
            SolveTime(id = "s-touch", timeInMillis = 10000L, timingDevice = TimingDevice.KEYBOARD),
            SolveTime(id = "s-bt", timeInMillis = 11000L, timingDevice = TimingDevice.EXTERNAL_TIMER),
            SolveTime(id = "s-cube", timeInMillis = 12000L, timingDevice = TimingDevice.SMART_CUBE)
        )

        val rows = parseRows(CsvExporter.exportSolvesToString(solves) { "Session" })

        assertEquals("timing_device", rows[1].last())
        assertEquals(listOf("keyboard", "external_timer", "smart_cube"), rows.drop(2).map { it.last() })
    }

    @Test
    fun exportSolves_formulaLikeTextFields_arePrefixedWithSingleQuote() {
        val solves = listOf(
            SolveTime(id = "s1", timeInMillis = 10000L, scramble = "=1+1", sessionId = "sess-1"),
            SolveTime(id = "s2", timeInMillis = 10000L, scramble = "+cmd|' /C calc'!A0", sessionId = "sess-1"),
            SolveTime(id = "s3", timeInMillis = 10000L, scramble = "-2+3", sessionId = "sess-1"),
            SolveTime(id = "s4", timeInMillis = 10000L, scramble = "@SUM(1+1)", sessionId = "sess-1"),
            SolveTime(id = "s5", timeInMillis = 10000L, scramble = "\t=1+1", sessionId = "sess-1"),
            SolveTime(id = "s6", timeInMillis = 10000L, scramble = "\r=1+1", sessionId = "sess-1")
        )

        val rows = parseRows(CsvExporter.exportSolvesToString(solves) { "=HYPERLINK(\"http://evil\",\"x\")" })

        val scrambleColumn = CsvFormat.COLUMNS.indexOf("scramble")
        val sessionNameColumn = CsvFormat.COLUMNS.indexOf("session_name")
        val dataRows = rows.drop(2)
        assertEquals(
            listOf("'=1+1", "'+cmd|' /C calc'!A0", "'-2+3", "'@SUM(1+1)", "'\t=1+1", "'\r=1+1"),
            dataRows.map { it[scrambleColumn] }
        )
        dataRows.forEach { assertEquals("'=HYPERLINK(\"http://evil\",\"x\")", it[sessionNameColumn]) }
    }

    @Test
    fun exportSolves_formulaLikeIds_arePrefixedWithSingleQuote() {
        val solve = SolveTime(id = "-solve", timeInMillis = 10000L, sessionId = "@session")

        val row = parseRows(CsvExporter.exportSolvesToString(listOf(solve)) { "Session" })[2]

        assertEquals("'-solve", row[CsvFormat.COLUMNS.indexOf("solve_id")])
        assertEquals("'@session", row[CsvFormat.COLUMNS.indexOf("session_id")])
    }

    @Test
    fun exportSolves_numericPenaltyAndPuzzleColumns_areNeverPrefixed() {
        val solve = SolveTime(
            id = "s-plus2",
            timeInMillis = 10000L,
            penalty = Penalty.PLUS_TWO,
            scramble = "R U",
            sessionId = "sess-1"
        )

        val row = parseRows(CsvExporter.exportSolvesToString(listOf(solve)) { "Session" })[2]

        assertEquals("+2", row[CsvFormat.COLUMNS.indexOf("penalty")])
        assertEquals("10000", row[CsvFormat.COLUMNS.indexOf("time")])
        assertEquals("3x3", row[CsvFormat.COLUMNS.indexOf("puzzle")])
        assertEquals("R U", row[CsvFormat.COLUMNS.indexOf("scramble")])
    }
}
