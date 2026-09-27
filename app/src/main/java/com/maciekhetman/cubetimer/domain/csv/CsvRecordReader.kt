package com.maciekhetman.cubetimer.domain.csv

import java.io.Closeable
import java.io.PushbackReader
import java.io.Reader

/**
 * Streaming RFC 4180 compliant CSV parser.
 *
 * Implements a character-level pushback state machine to handle:
 * - Multi-line quoted fields (embedded CRLF / LF do not terminate records)
 * - Escaped double quotes ("" -> ")
 * - Commas within quoted fields
 * - Varied line terminators (\r\n, \n, \r)
 * - Empty fields and consecutive delimiters
 * - Transparent UTF-8 BOM (\uFEFF) stripping
 */
class CsvRecordReader(reader: Reader) : Closeable {

    private val pushback: PushbackReader = CsvFormat.createBomStrippingReader(reader)

    /**
     * Reads the next record from the stream as a list of unescaped string fields.
     * Returns null when the end of stream is reached.
     */
    fun readNextRecord(): List<String>? {
        val fields = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var hasReadAnyChar = false

        while (true) {
            val cInt = pushback.read()
            if (cInt == -1) {
                if (!hasReadAnyChar && fields.isEmpty() && currentField.isEmpty()) {
                    return null
                }
                fields.add(currentField.toString())
                return fields
            }
            hasReadAnyChar = true
            val c = cInt.toChar()

            if (inQuotes) {
                if (c == '"') {
                    val nextInt = pushback.read()
                    if (nextInt != -1 && nextInt.toChar() == '"') {
                        // Escaped quote: "" inside a quoted field resolves to a literal "
                        currentField.append('"')
                    } else {
                        // Closing quote: unread lookahead and exit quoted state
                        if (nextInt != -1) {
                            pushback.unread(nextInt)
                        }
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        fields.add(currentField.toString())
                        currentField.clear()
                    }
                    '\r' -> {
                        val nextInt = pushback.read()
                        if (nextInt != -1 && nextInt.toChar() != '\n') {
                            pushback.unread(nextInt)
                        }
                        fields.add(currentField.toString())
                        return fields
                    }
                    '\n' -> {
                        fields.add(currentField.toString())
                        return fields
                    }
                    else -> currentField.append(c)
                }
            }
        }
    }

    override fun close() {
        pushback.close()
    }
}
