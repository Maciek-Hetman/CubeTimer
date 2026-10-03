package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.model.Penalty
import java.io.PushbackReader
import java.io.Reader

/**
 * RFC 4180 CSV formatting rules, constants, and tokenizer utilities for CubeTimer.
 */
object CsvFormat {

    /**
     * Mandatory first-line magic comment identifying CubeTimer export files.
     */
    const val COMMENT_LINE = "# Source: CubeTimer"

    /**
     * Standard RFC 4180 record delimiter (CRLF).
     */
    const val CRLF = "\r\n"

    /**
     * Columns every importable file must carry.
     */
    val REQUIRED_COLUMNS = listOf(
        "solve_id",
        "session_id",
        "session_name",
        "puzzle",
        "timestamp",
        "time",
        "penalty",
        "scramble"
    )

    /**
     * Optional column: files without it (older exports) import as keyboard-timed.
     */
    const val TIMING_DEVICE_COLUMN = "timing_device"

    /**
     * Canonical column names in order.
     */
    val COLUMNS = REQUIRED_COLUMNS + TIMING_DEVICE_COLUMN

    /**
     * Canonical header row string.
     */
    const val HEADER_LINE = "solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble,timing_device"

    /**
     * Leading characters that make a spreadsheet evaluate a cell as a formula.
     */
    private const val FORMULA_TRIGGERS = "=+-@\t\r"

    /**
     * Marker spreadsheets treat as "this cell is text"; it is not displayed.
     */
    private const val TEXT_MARKER = '\''

    /**
     * Unicode UTF-8 Byte Order Mark character (\uFEFF).
     */
    const val UTF8_BOM_CHAR = '\uFEFF'

    /**
     * Escapes a single CSV field value according to RFC 4180 rules:
     * - If the field contains a comma (,), a double quote ("), a carriage return (\r),
     *   or a line feed (\n), it is enclosed in double quotes.
     * - Any double quote character (") inside the field is escaped by doubling it ("").
     * - Otherwise, the string is returned as-is.
     */
    fun escapeField(value: String): String {
        val needsQuotes = value.contains(',') ||
                value.contains('"') ||
                value.contains('\r') ||
                value.contains('\n')
        return if (needsQuotes) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    /**
     * Neutralizes CSV/formula injection in a free-text field: a value that starts with `=`, `+`, `-`,
     * `@`, tab or CR is prefixed with a single quote so spreadsheets show it as text instead of
     * evaluating it. A value that already starts with quotes followed by such a character gets one more,
     * so [unescapeFormula] can strip exactly one and the round trip stays lossless.
     */
    fun escapeFormula(value: String): String {
        val firstNonMarker = value.indexOfFirst { it != TEXT_MARKER }
        return if (firstNonMarker >= 0 && value[firstNonMarker] in FORMULA_TRIGGERS) TEXT_MARKER + value else value
    }

    /**
     * Inverse of [escapeFormula]: drops one leading quote when it is followed (after any further quotes)
     * by a formula-trigger character; every other value is returned unchanged.
     */
    fun unescapeFormula(value: String): String {
        if (value.isEmpty() || value[0] != TEXT_MARKER) return value
        val firstNonMarker = value.indexOfFirst { it != TEXT_MARKER }
        return if (firstNonMarker > 0 && value[firstNonMarker] in FORMULA_TRIGGERS) value.substring(1) else value
    }

    /**
     * Formats a list of field strings into an RFC 4180 CSV row ending in CRLF.
     */
    fun formatRow(fields: List<String>): String {
        return fields.joinToString(separator = ",", postfix = CRLF) { escapeField(it) }
    }

    /**
     * Converts a domain [Penalty] to its canonical CSV string representation ("none", "+2", "dnf").
     */
    fun formatPenalty(penalty: Penalty): String {
        return when (penalty) {
            Penalty.NONE -> "none"
            Penalty.PLUS_TWO -> "+2"
            Penalty.DNF -> "dnf"
        }
    }

    /**
     * Parses a raw CSV penalty string into domain [Penalty].
     * Tolerates "+2", "plus_two", "plus2", "dnf", "none", and null/blank inputs.
     */
    fun parsePenalty(value: String?): Penalty = CubeTypeConverters.toPenalty(value)

    /**
     * Wraps a [Reader] in a [PushbackReader] that silently consumes a leading
     * UTF-8 BOM (\uFEFF) if present at the start of the stream.
     */
    fun createBomStrippingReader(reader: Reader, bufferSize: Int = 4): PushbackReader {
        val pushback = PushbackReader(reader, maxOf(2, bufferSize))
        val firstChar = pushback.read()
        if (firstChar != -1 && firstChar.toChar() != UTF8_BOM_CHAR) {
            pushback.unread(firstChar)
        }
        return pushback
    }
}
