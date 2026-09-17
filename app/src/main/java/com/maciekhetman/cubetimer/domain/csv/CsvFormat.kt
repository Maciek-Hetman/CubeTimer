package com.maciekhetman.cubetimer.domain.csv

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
     * Canonical column names in order.
     */
    val COLUMNS = listOf(
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
     * Canonical header row string.
     */
    const val HEADER_LINE = "solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble"

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
    fun parsePenalty(value: String?): Penalty {
        return when (value?.trim()?.lowercase()) {
            "+2", "plus_two", "plus2" -> Penalty.PLUS_TWO
            "dnf" -> Penalty.DNF
            else -> Penalty.NONE
        }
    }

    /**
     * Strips a leading UTF-8 Byte Order Mark (\uFEFF) if present on the input string.
     */
    fun stripBom(input: String): String {
        return input.removePrefix(UTF8_BOM_CHAR.toString())
    }

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
