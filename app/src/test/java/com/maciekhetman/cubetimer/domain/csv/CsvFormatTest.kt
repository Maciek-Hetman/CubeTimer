package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.model.Penalty
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class CsvFormatTest {

    // --- RFC 4180 Escaping Tests ---

    @Test
    fun escapeField_plainText_returnsUnchanged() {
        assertEquals("3x3", CsvFormat.escapeField("3x3"))
        assertEquals("R U R' U'", CsvFormat.escapeField("R U R' U'"))
        assertEquals("123456", CsvFormat.escapeField("123456"))
    }

    @Test
    fun escapeField_withComma_wrapsInQuotes() {
        assertEquals("\"3x3, Main\"", CsvFormat.escapeField("3x3, Main"))
        assertEquals("\"[R, U]\"", CsvFormat.escapeField("[R, U]"))
    }

    @Test
    fun escapeField_withDoubleQuote_doublesQuotesAndWraps() {
        assertEquals("\"3\"\" Cube\"", CsvFormat.escapeField("3\" Cube"))
        assertEquals("\"\"\"Hello\"\"\"", CsvFormat.escapeField("\"Hello\""))
    }

    @Test
    fun escapeField_withNewline_wrapsInQuotes() {
        assertEquals("\"Line1\nLine2\"", CsvFormat.escapeField("Line1\nLine2"))
        assertEquals("\"Line1\rLine2\"", CsvFormat.escapeField("Line1\rLine2"))
        assertEquals("\"Line1\r\nLine2\"", CsvFormat.escapeField("Line1\r\nLine2"))
    }

    @Test
    fun escapeField_withCommasQuotesAndNewlines_escapesComprehensively() {
        val input = "Special, \"Session\"\nPart 2"
        val expected = "\"Special, \"\"Session\"\"\nPart 2\""
        assertEquals(expected, CsvFormat.escapeField(input))
    }

    @Test
    fun escapeField_emptyString_returnsEmptyString() {
        assertEquals("", CsvFormat.escapeField(""))
    }

    @Test
    fun escapeField_singleDoubleQuote_escapesToFourQuotes() {
        assertEquals("\"\"\"\"", CsvFormat.escapeField("\""))
    }

    // --- Row Formatting Tests ---

    @Test
    fun formatRow_simpleFields_joinsWithCommaAndCrlf() {
        val fields = listOf("id-1", "sess-1", "Main", "3x3", "1000", "12000", "none", "R U R'")
        val expected = "id-1,sess-1,Main,3x3,1000,12000,none,R U R'\r\n"
        assertEquals(expected, CsvFormat.formatRow(fields))
    }

    @Test
    fun formatRow_escapedFields_escapesCorrectly() {
        val fields = listOf("id-1", "", "3x3, \"Best\"", "3x3", "1000", "12000", "+2", "[R, U]\nF'")
        val expected = "id-1,,\"3x3, \"\"Best\"\"\",3x3,1000,12000,+2,\"[R, U]\nF'\"\r\n"
        assertEquals(expected, CsvFormat.formatRow(fields))
    }

    // --- Penalty Formatting & Parsing Tests ---

    @Test
    fun formatPenalty_mapsAllPenalties() {
        assertEquals("none", CsvFormat.formatPenalty(Penalty.NONE))
        assertEquals("+2", CsvFormat.formatPenalty(Penalty.PLUS_TWO))
        assertEquals("dnf", CsvFormat.formatPenalty(Penalty.DNF))
    }

    @Test
    fun parsePenalty_parsesAllVariants() {
        assertEquals(Penalty.NONE, CsvFormat.parsePenalty("none"))
        assertEquals(Penalty.NONE, CsvFormat.parsePenalty(""))
        assertEquals(Penalty.NONE, CsvFormat.parsePenalty(null))
        assertEquals(Penalty.NONE, CsvFormat.parsePenalty("unknown"))

        assertEquals(Penalty.PLUS_TWO, CsvFormat.parsePenalty("+2"))
        assertEquals(Penalty.PLUS_TWO, CsvFormat.parsePenalty("plus_two"))
        assertEquals(Penalty.PLUS_TWO, CsvFormat.parsePenalty("plus2"))
        assertEquals(Penalty.PLUS_TWO, CsvFormat.parsePenalty(" +2 "))

        assertEquals(Penalty.DNF, CsvFormat.parsePenalty("dnf"))
        assertEquals(Penalty.DNF, CsvFormat.parsePenalty("DNF"))
        assertEquals(Penalty.DNF, CsvFormat.parsePenalty(" dnf "))
    }

    // --- UTF-8 BOM Handling Tests ---

    @Test
    fun stripBom_removesLeadingBom() {
        val withBom = "\uFEFF# Source: CubeTimer"
        assertEquals("# Source: CubeTimer", CsvFormat.stripBom(withBom))
    }

    @Test
    fun stripBom_withoutBom_returnsOriginal() {
        val withoutBom = "# Source: CubeTimer"
        assertEquals("# Source: CubeTimer", CsvFormat.stripBom(withoutBom))
    }

    @Test
    fun createBomStrippingReader_stripsBomAtStreamStart() {
        val input = "\uFEFF# Source: CubeTimer\r\nsolve_id,..."
        val reader = CsvFormat.createBomStrippingReader(StringReader(input))
        val firstChar = reader.read().toChar()
        assertEquals('#', firstChar)
    }

    @Test
    fun createBomStrippingReader_preservesFirstCharWhenNoBom() {
        val input = "# Source: CubeTimer\r\nsolve_id,..."
        val reader = CsvFormat.createBomStrippingReader(StringReader(input))
        val firstChar = reader.read().toChar()
        assertEquals('#', firstChar)
    }

    @Test
    fun createBomStrippingReader_handlesEmptyInput() {
        val reader = CsvFormat.createBomStrippingReader(StringReader(""))
        assertEquals(-1, reader.read())
    }
}
