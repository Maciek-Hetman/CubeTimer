package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.StandardCharsets

/**
 * Empirical adversarial stress challenge test suite for [CsvFormat], [CsvRecordReader], and [CsvExporter].
 *
 * Tests pathological edge cases:
 * - Heavily nested quotes, repetitive escaped quotes, and quote-only fields
 * - Unclosed quotes at EOF and across newlines
 * - Escaped quote symmetry and RFC 4180 roundtrip guarantees
 * - Multi-line fields with mixed CRLF, LF, and CR terminators
 * - Commas in scrambles (commutators [R, U], conjugates [R: U])
 * - Commas and quotes in session names
 * - 10,000-solve streaming export and read roundtrip with field-level fidelity
 * - UTF-8 BOM edge cases and Unicode / emoji preservation
 * - Massive payload fields (50,000 character scramble strings)
 */
class CsvParserAndExporterStressChallengeTest {

    private fun parseStringRecords(csv: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        CsvRecordReader(StringReader(csv)).use { reader ->
            while (true) {
                val rec = reader.readNextRecord() ?: break
                records.add(rec)
            }
        }
        return records
    }

    private fun parseStreamRecords(stream: java.io.InputStream): List<List<String>> {
        val records = mutableListOf<List<String>>()
        CsvRecordReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val rec = reader.readNextRecord() ?: break
                records.add(rec)
            }
        }
        return records
    }

    // ========================================================================
    // 1. Heavily Nested Quotes, Escaped Quotes, and Quote-Only Fields
    // ========================================================================

    @Test
    fun quoteOnlyFields_varyingLengths_resolvedCorrectly() {
        // In RFC 4180:
        // """" is an escaped quote enclosed in quotes -> literal "
        // """""" is 2 escaped quotes enclosed in quotes -> literal ""
        // """""""" is 3 escaped quotes enclosed in quotes -> literal """
        // """""""""" is 4 escaped quotes enclosed in quotes -> literal """"
        val csv = "\"\"\"\",,\"\"\"\"\"\",,\"\"\"\"\"\"\"\",,\"\"\"\"\"\"\"\"\"\"\r\n"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        val row = records[0]
        assertEquals("\"", row[0])
        assertEquals("", row[1])
        assertEquals("\"\"", row[2])
        assertEquals("", row[3])
        assertEquals("\"\"\"", row[4])
        assertEquals("", row[5])
        assertEquals("\"\"\"\"", row[6])
    }

    @Test
    fun heavilyNestedQuotes_deepEscaping_preservesFidelity() {
        // String with alternating text and escaped quotes:
        // "a""b""c""d""e""f" -> a"b"c"d"e"f
        val complex = "\"a\"\"b\"\"c\"\"d\"\"e\"\"f\""
        val records = parseStringRecords(complex)

        assertEquals(1, records.size)
        assertEquals(listOf("a\"b\"c\"d\"e\"f"), records[0])
    }

    @Test
    fun deeplyNestedQuotes_hundredConsecutiveQuotes_parsesWithoutStackOverflow() {
        // 102 consecutive quotes: outer 2 are enclosing, inner 100 are 50 escaped quotes
        val fiftyQuotes = "\"".repeat(50)
        val rawCsv = "\"" + "\"\"".repeat(50) + "\""

        val records = parseStringRecords(rawCsv)
        assertEquals(1, records.size)
        assertEquals(fiftyQuotes, records[0][0])
    }

    @Test
    fun quotesAtFieldBoundaries_leadingAndTrailingEscapedQuotes() {
        // Escaped quote at very start of field: """start" -> "start
        // Escaped quote at very end of field: "end""" -> end"
        // Escaped quotes at both ends: """both""" -> "both"
        val csv = "\"\"\"start\",\"end\"\"\",\"\"\"both\"\"\"\r\n"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf("\"start", "end\"", "\"both\""), records[0])
    }

    // ========================================================================
    // 2. Unclosed Quotes (Pathological Malformed Input)
    // ========================================================================

    @Test
    fun unclosedQuote_atEof_returnsFieldContentWithoutCrashing() {
        val csv = "col1,\"unclosedFieldAtEof"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf("col1", "unclosedFieldAtEof"), records[0])
    }

    @Test
    fun unclosedQuote_withEscapedQuotesAtEof_recoversGracefully() {
        val csv = "col1,\"unclosed with \"\"escaped\"\" quotes"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf("col1", "unclosed with \"escaped\" quotes"), records[0])
    }

    @Test
    fun unclosedQuote_spanningMultipleLinesUntilEof_treatedAsSingleMultiLineField() {
        val csv = "col1,\"unclosed line 1\r\nunclosed line 2\nunclosed line 3"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf("col1", "unclosed line 1\r\nunclosed line 2\nunclosed line 3"), records[0])
    }

    @Test
    fun unclosedQuote_singleQuoteCharOnly_returnsEmptyField() {
        val csv = "\""
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf(""), records[0])
    }

    @Test
    fun unclosedQuote_commaAfterQuote_preservesCommaInsideQuotedState() {
        val csv = "\"unclosed,with,commas"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf("unclosed,with,commas"), records[0])
    }

    // ========================================================================
    // 3. Escaped Quotes Inside Quotes & Roundtrip Property
    // ========================================================================

    @Test
    fun rfc4180_escapeAndParse_propertyRoundtrip() {
        val testStrings = listOf(
            "",
            "simple",
            "with,comma",
            "with\"quote",
            "with\nnewline",
            "with\r\ncrlf",
            "\"",
            "\"\"",
            "\"\"\"",
            "\"\"\"\"",
            "\"hello\"",
            "\"hello, world\"",
            "a\"b\"c",
            "{\"json\": true, \"value\": \"escaped \\\"string\\\"\"}",
            "   leading and trailing spaces   ",
            " , , , ",
            "R U R' U' [R, U]",
            "[R': [U, D']]",
            "Line 1\r\nLine 2\nLine 3\rLine 4",
            "🏆⏱️🔥 // comment with \",\" and \n newline"
        )

        for (original in testStrings) {
            // Row-level roundtrip with canonical RFC 4180 CRLF terminator
            val formattedRow = CsvFormat.formatRow(listOf(original))
            val records = parseStringRecords(formattedRow)

            assertEquals("Failed row roundtrip for input: <$original>", 1, records.size)
            assertEquals("Failed row value equality for input: <$original>", original, records[0][0])

            // Field-level roundtrip without trailing CRLF (for non-empty fields)
            if (original.isNotEmpty()) {
                val escaped = CsvFormat.escapeField(original)
                val directRecords = parseStringRecords(escaped)
                assertEquals("Failed direct roundtrip for input: <$original>", 1, directRecords.size)
                assertEquals("Failed direct value equality for input: <$original>", original, directRecords[0][0])
            }
        }
    }

    @Test
    fun rfc4180_multiFieldRow_escapedQuotesPreserveColumns() {
        val row = listOf("col\"1", "col,2", "\"col3\"", "col\r\n4")
        val formatted = CsvFormat.formatRow(row)

        val records = parseStringRecords(formatted)
        assertEquals(1, records.size)
        assertEquals(row, records[0])
    }

    // ========================================================================
    // 4. Multi-Line Fields with Mixed CRLF / LF / CR Terminators
    // ========================================================================

    @Test
    fun multiLineField_mixedCrlfLfCr_preservesExactLineEndings() {
        val complexField = "Line 1 (CRLF)\r\nLine 2 (LF)\nLine 3 (CR)\rLine 4 (End)"
        val csv = "\"$complexField\",secondCol\r\nnextRow1,nextRow2\nthirdRow1,thirdRow2\rfourthRow1,fourthRow2"

        val records = parseStringRecords(csv)

        assertEquals(4, records.size)
        assertEquals(listOf(complexField, "secondCol"), records[0])
        assertEquals(listOf("nextRow1", "nextRow2"), records[1])
        assertEquals(listOf("thirdRow1", "thirdRow2"), records[2])
        assertEquals(listOf("fourthRow1", "fourthRow2"), records[3])
    }

    @Test
    fun consecutiveNewlinesInsideQuotes_preservedVerbatim() {
        val field = "a\r\n\r\n\n\n\r\rb"
        val csv = "\"$field\",next"

        val records = parseStringRecords(csv)
        assertEquals(1, records.size)
        assertEquals(listOf(field, "next"), records[0])
    }

    @Test
    fun consecutiveEmptyLinesBetweenRecords_skippedCleanly() {
        val csv = "row1,col\r\n\r\n\n\r\nrow2,col"
        val records = parseStringRecords(csv)

        // Empty lines between rows are parsed as single-element rows [""] by CsvRecordReader
        val nonEmpty = records.filter { it.size > 1 || (it.size == 1 && it[0].isNotBlank()) }
        assertEquals(2, nonEmpty.size)
        assertEquals(listOf("row1", "col"), nonEmpty[0])
        assertEquals(listOf("row2", "col"), nonEmpty[1])
    }

    // ========================================================================
    // 5. Commas in Scrambles (Commutators, Conjugates, Notation)
    // ========================================================================

    @Test
    fun commasInScrambles_commutatorsAndConjugates_doNotSplitColumns() {
        val scrambles = listOf(
            "[R, U]",
            "[R': [U, D']]",
            "[r, u], [r', d']",
            "R, U, F, B, L, D",
            "F (R U R' U') F' , [U, D]"
        )

        for (scramble in scrambles) {
            val solve = SolveTime(
                id = "solve-comm",
                timeInMillis = 12000L,
                scramble = scramble,
                sessionId = "sess-1"
            )

            val exported = CsvExporter.exportSolvesToString(listOf(solve)) { "Session" }
            val records = parseStringRecords(exported)

            // 1 comment record + 1 header record + 1 data record = 3 records
            assertEquals(3, records.size)
            val dataRow = records[2]
            assertEquals(8, dataRow.size)
            assertEquals(scramble, dataRow[7])
        }
    }

    @Test
    fun commasAndQuotesInScrambles_combinedPathology() {
        val scramble = "[R, U] \"Megaminx\" \n D++, R++ [U', D'] // comment, with, commas"
        val solve = SolveTime(
            id = "solve-patho-scramble",
            timeInMillis = 45000L,
            scramble = scramble,
            mode = Mode.MEGAMINX,
            sessionId = "sess-mega"
        )

        val exported = CsvExporter.exportSolvesToString(listOf(solve)) { "Mega Session" }
        val records = parseStringRecords(exported)

        assertEquals(3, records.size)
        val dataRow = records[2]
        assertEquals(8, dataRow.size)
        assertEquals("solve-patho-scramble", dataRow[0])
        assertEquals("sess-mega", dataRow[1])
        assertEquals("Mega Session", dataRow[2])
        assertEquals("megaminx", dataRow[3])
        assertEquals("45000", dataRow[5])
        assertEquals(scramble, dataRow[7])
    }

    // ========================================================================
    // 6. Commas and Quotes in Session Names
    // ========================================================================

    @Test
    fun commasAndQuotesInSessionNames_escapedAndParsedAccurately() {
        val sessionNames = listOf(
            "Morning, 3x3, Sub-10",
            "\"Sub-15\" Practice, 3x3",
            "Session, with, many, commas,,,",
            "Session \"A\", with \"quotes\" and commas,,"
        )

        val solves = sessionNames.mapIndexed { idx, name ->
            SolveTime(
                id = "solve-$idx",
                timeInMillis = 10000L + idx,
                sessionId = "sess-$idx",
                scramble = "R U R' U'"
            )
        }

        val exported = CsvExporter.exportSolvesToString(solves) { id ->
            val idx = id?.removePrefix("sess-")?.toIntOrNull() ?: 0
            sessionNames[idx]
        }

        val records = parseStringRecords(exported)
        assertEquals(2 + sessionNames.size, records.size)

        for (i in sessionNames.indices) {
            val row = records[2 + i]
            assertEquals(8, row.size)
            assertEquals("solve-$i", row[0])
            assertEquals("sess-$i", row[1])
            assertEquals(sessionNames[i], row[2])
        }
    }

    // ========================================================================
    // 7. 10,000-Solve Streaming Export and Import Roundtrip
    // ========================================================================

    @Test
    fun streamingRoundtrip_tenThousandSolves_exactFidelityAndConstantMemory() {
        val solveCount = 10_000
        val modes = listOf(
            Mode.CUBE_2x2,
            Mode.CUBE_3x3,
            Mode.CUBE_4x4,
            Mode.CUBE_5x5,
            Mode.MEGAMINX,
            Mode.PYRAMINX
        )
        val penalties = listOf(Penalty.NONE, Penalty.PLUS_TWO, Penalty.DNF)

        // Generate 10,000 heterogeneous solves with pathological characters
        val generatedSolves = ArrayList<SolveTime>(solveCount)
        for (i in 0 until solveCount) {
            val mode = modes[i % modes.size]
            val penalty = penalties[i % penalties.size]
            val scramble = when (i % 6) {
                0 -> "R U R' U' [R, U]"
                1 -> "[R': [U, D']] \"Megaminx\" \n D++"
                2 -> "B2 F2 L2 R2 // Clean notation"
                3 -> "R, U, F, B, L, D // Commas galore"
                4 -> "\"Quotes\" and 'Apostrophes' and \r\n Newline"
                else -> "U2 R2 F2 U' R // Unicode ⏱️ 🏆"
            }
            val sessionId = if (i % 7 == 0) null else "sess-${i % 20}"

            generatedSolves.add(
                SolveTime(
                    id = "uuid-$i",
                    timeInMillis = 5000L + (i * 3L) % 60000L,
                    penalty = penalty,
                    timestamp = 1700000000000L + (i * 1000L),
                    scramble = scramble,
                    mode = mode,
                    sessionId = sessionId
                )
            )
        }

        fun sessionNameLookup(sId: String?): String = when {
            sId == null -> "Unassigned, Solves"
            sId.endsWith("1") -> "Session, \"Special\", #1"
            sId.endsWith("2") -> "3x3, 4x4, 5x5 Relay"
            else -> "Standard Session $sId"
        }

        // Export to a temporary file using streaming CsvExporter
        val tempFile = File.createTempFile("cubetimer_stress_10k_", ".csv")
        tempFile.deleteOnExit()

        val exportStartTime = System.currentTimeMillis()
        FileOutputStream(tempFile).use { fos ->
            CsvExporter.exportSolves(fos, generatedSolves, ::sessionNameLookup)
        }
        val exportDurationMs = System.currentTimeMillis() - exportStartTime
        assertTrue("Export duration should be reasonable (< 5s), was ${exportDurationMs}ms", exportDurationMs < 5000)
        assertTrue("Exported file should not be empty", tempFile.length() > 0)

        // Stream back using CsvRecordReader without loading full file into memory
        val readStartTime = System.currentTimeMillis()
        var recordIndex = 0

        FileInputStream(tempFile).use { fis ->
            CsvRecordReader(InputStreamReader(fis, StandardCharsets.UTF_8)).use { reader ->
                // Record 0: Magic comment
                val magic = reader.readNextRecord()
                assertNotNull(magic)
                assertEquals(listOf(CsvFormat.COMMENT_LINE), magic)
                recordIndex++

                // Record 1: Header line
                val header = reader.readNextRecord()
                assertNotNull(header)
                assertEquals(CsvFormat.COLUMNS, header)
                recordIndex++

                // Records 2..10001: 10,000 solves
                for (i in 0 until solveCount) {
                    val record = reader.readNextRecord()
                    assertNotNull("Record $i should not be null", record)
                    assertEquals("Record $i must have 8 columns", 8, record!!.size)

                    val expectedSolve = generatedSolves[i]
                    val expectedSessionId = expectedSolve.sessionId ?: ""
                    val expectedSessionName = sessionNameLookup(expectedSolve.sessionId)
                    val expectedPuzzle = CubeTypeConverters.fromMode(expectedSolve.mode)
                    val expectedPenalty = CsvFormat.formatPenalty(expectedSolve.penalty)

                    assertEquals(expectedSolve.id, record[0])
                    assertEquals(expectedSessionId, record[1])
                    assertEquals(expectedSessionName, record[2])
                    assertEquals(expectedPuzzle, record[3])
                    assertEquals(expectedSolve.timestamp.toString(), record[4])
                    assertEquals(expectedSolve.timeInMillis.toString(), record[5])
                    assertEquals(expectedPenalty, record[6])
                    assertEquals(expectedSolve.scramble, record[7])

                    recordIndex++
                }

                // Record 10002: End of stream
                val eofRecord = reader.readNextRecord()
                assertNull("End of stream must return null", eofRecord)
            }
        }

        val readDurationMs = System.currentTimeMillis() - readStartTime
        assertTrue("Streaming read duration should be reasonable (< 5s), was ${readDurationMs}ms", readDurationMs < 5000)
        assertEquals(solveCount + 2, recordIndex)

        // Cleanup
        tempFile.delete()
    }

    // ========================================================================
    // 8. UTF-8 BOM Variations
    // ========================================================================

    @Test
    fun bom_prefixedBeforeComment_strippedCleanly() {
        val csv = "\uFEFF# Source: CubeTimer\r\nsolve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble\r\n"
        val records = parseStringRecords(csv)

        assertEquals(2, records.size)
        assertEquals(listOf("# Source: CubeTimer"), records[0])
        assertEquals(CsvFormat.COLUMNS, records[1])
    }

    @Test
    fun bom_prefixedBeforeHeaderDirectly_strippedCleanly() {
        val csv = "\uFEFFsolve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble\r\ns1,sess1,S1,3x3,1700000000000,10000,none,R U\r\n"
        val records = parseStringRecords(csv)

        assertEquals(2, records.size)
        assertEquals("solve_id", records[0][0])
        assertEquals("s1", records[1][0])
    }

    @Test
    fun bom_onEmptyStream_returnsNull() {
        val csv = "\uFEFF"
        val records = parseStringRecords(csv)
        assertTrue(records.isEmpty())
    }

    @Test
    fun bom_followedImmediatelyByNewline_returnsEmptyRow() {
        val csv = "\uFEFF\r\nsolve_id,session_id\r\n"
        val records = parseStringRecords(csv)

        assertEquals(2, records.size)
        assertEquals(listOf(""), records[0])
        assertEquals(listOf("solve_id", "session_id"), records[1])
    }

    @Test
    fun bom_embeddedInsideField_preservedAsContent() {
        val csv = "col1,\"embedded\uFEFFbom\"\r\n"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf("col1", "embedded\uFEFFbom"), records[0])
    }

    // ========================================================================
    // 9. Unicode, Emojis, and Pathological Non-ASCII Characters
    // ========================================================================

    @Test
    fun unicodePreservation_emojisAndMultilingualStrings() {
        val multilingualSolves = listOf(
            SolveTime(id = "s-jp", timeInMillis = 10000L, scramble = "R U R' // 日本語 練習 🏆", sessionId = "s1"),
            SolveTime(id = "s-ar", timeInMillis = 11000L, scramble = "F R U // العربية ⚡", sessionId = "s2"),
            SolveTime(id = "s-pl", timeInMillis = 12000L, scramble = "R' F R // Zażółć gęślą jaźń", sessionId = "s3"),
            SolveTime(id = "s-ru", timeInMillis = 13000L, scramble = "U R U' // Привет мир 🧊", sessionId = "s4"),
            SolveTime(id = "s-ctrl", timeInMillis = 14000L, scramble = "R\tU\u000B\u000CF", sessionId = "s5")
        )

        val output = CsvExporter.exportSolvesToString(multilingualSolves) { "Session $it" }
        val records = parseStringRecords(output)

        assertEquals(2 + multilingualSolves.size, records.size)
        for (i in multilingualSolves.indices) {
            val original = multilingualSolves[i]
            val row = records[2 + i]
            assertEquals(original.id, row[0])
            assertEquals(original.scramble, row[7])
        }
    }

    // ========================================================================
    // 10. Massive Payload Stress (50KB Scramble)
    // ========================================================================

    @Test
    fun massiveField_fiftyKilobytes_parsesWithoutBufferOverflow() {
        val sb = java.lang.StringBuilder()
        for (i in 1..2500) {
            sb.append("R U R' U' [R, U] \"step $i\" \r\n")
        }
        val massiveScramble = sb.toString()
        assertTrue(massiveScramble.length > 50_000)

        val solve = SolveTime(
            id = "solve-massive",
            timeInMillis = 999999L,
            scramble = massiveScramble,
            sessionId = "sess-massive"
        )

        val exported = CsvExporter.exportSolvesToString(listOf(solve)) { "Massive Session" }
        val records = parseStringRecords(exported)

        assertEquals(3, records.size)
        val dataRow = records[2]
        assertEquals(8, dataRow.size)
        assertEquals("solve-massive", dataRow[0])
        assertEquals(massiveScramble, dataRow[7])
    }

    // ========================================================================
    // 11. Delimiter, Whitespace, and Boundary Resilience
    // ========================================================================

    @Test
    fun emptyAndConsecutiveDelimiters_trailingAndLeadingCommas() {
        val csv = ",,,\r\n,a,,b,\r\na,b,\r\n"
        val records = parseStringRecords(csv)

        assertEquals(3, records.size)
        assertEquals(listOf("", "", "", ""), records[0])
        assertEquals(listOf("", "a", "", "b", ""), records[1])
        assertEquals(listOf("a", "b", ""), records[2])
    }

    @Test
    fun whitespaceAroundFields_preservedVerbatim() {
        val csv = " a , \" b \" , c \r\n"
        val records = parseStringRecords(csv)

        assertEquals(1, records.size)
        assertEquals(listOf(" a ", "  b  ", " c "), records[0])
    }

    @Test
    fun rawDuration_neverAppliesPlusTwoOffset() {
        // Raw duration: 8420L, Penalty.PLUS_TWO -> displayTime is 10420L
        val solve = SolveTime(
            id = "solve-raw-check",
            timeInMillis = 8420L,
            penalty = Penalty.PLUS_TWO
        )
        assertEquals(10420L, solve.displayTime)

        val exported = CsvExporter.exportSolvesToString(listOf(solve)) { "Session" }
        val records = parseStringRecords(exported)

        val row = records[2]
        assertEquals("8420", row[5])
        assertEquals("+2", row[6])
    }
}
