package com.maciekhetman.cubetimer.domain.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class CsvRecordReaderTest {

    @Test
    fun readNextRecord_crlfSeparated_parsesRows() {
        val input = "a,b,c\r\nd,e,f\r\n"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("a", "b", "c"), reader.readNextRecord())
            assertEquals(listOf("d", "e", "f"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_lfSeparated_parsesRows() {
        val input = "a,b,c\nd,e,f\n"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("a", "b", "c"), reader.readNextRecord())
            assertEquals(listOf("d", "e", "f"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_crSeparated_parsesRows() {
        val input = "a,b,c\rd,e,f\r"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("a", "b", "c"), reader.readNextRecord())
            assertEquals(listOf("d", "e", "f"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_noTrailingNewline_parsesLastRow() {
        val input = "a,b,c\nd,e,f"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("a", "b", "c"), reader.readNextRecord())
            assertEquals(listOf("d", "e", "f"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_quotedFieldWithComma_preservesField() {
        val input = "\"hello, world\",b,c"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("hello, world", "b", "c"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_quotedFieldWithEscapedQuotes_decodesQuotes() {
        val input = "\"hello \"\"world\"\"\",b"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("hello \"world\"", "b"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_quotedFieldWithNewline_doesNotSplitRow() {
        val input = "\"line1\nline2\",b\r\nc,d"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("line1\nline2", "b"), reader.readNextRecord())
            assertEquals(listOf("c", "d"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_quotedFieldWithCrlf_doesNotSplitRow() {
        val input = "\"line1\r\nline2\",b\r\nc,d"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("line1\r\nline2", "b"), reader.readNextRecord())
            assertEquals(listOf("c", "d"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_emptyStream_returnsNull() {
        val input = ""
        CsvRecordReader(StringReader(input)).use { reader ->
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_bomPrefixed_stripsBom() {
        val input = "\uFEFF# Source: CubeTimer\r\na,b"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("# Source: CubeTimer"), reader.readNextRecord())
            assertEquals(listOf("a", "b"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_emptyAndConsecutiveDelimiters() {
        val input = ",,,\r\na,,b,"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("", "", "", ""), reader.readNextRecord())
            assertEquals(listOf("a", "", "b", ""), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun readNextRecord_unclosedQuoteAtEof_returnsFieldWithoutException() {
        val input = "a,\"unclosed"
        CsvRecordReader(StringReader(input)).use { reader ->
            assertEquals(listOf("a", "unclosed"), reader.readNextRecord())
            assertNull(reader.readNextRecord())
        }
    }

    @Test
    fun close_closesUnderlyingReader() {
        var closed = false
        val customReader = object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int = -1
            override fun close() {
                closed = true
            }
        }
        val csvReader = CsvRecordReader(customReader)
        csvReader.close()
        assertTrue(closed)
    }
}
