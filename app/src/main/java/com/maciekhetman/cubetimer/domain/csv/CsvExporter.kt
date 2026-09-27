package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.model.SolveTime
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Service responsible for streaming speedcubing solve records to CSV output streams.
 */
object CsvExporter {

    /**
     * Streams solves formatted as RFC 4180 CSV into [outputStream].
     *
     * Writes Line 1 magic comment (# Source: CubeTimer), Line 2 header, and
     * each solve formatted with RFC 4180 escaped fields and CRLF line endings.
     *
     * Flushes the buffer upon completion without closing the underlying stream.
     *
     * @param outputStream Target stream (e.g. from Android Storage Access Framework).
     * @param solves List of solves to export.
     * @param sessionNameLookup Mapping function from nullable sessionId to human-readable session name.
     */
    fun exportSolves(
        outputStream: OutputStream,
        solves: List<SolveTime>,
        sessionNameLookup: (String?) -> String
    ) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))

        // Line 1: CubeTimer magic comment
        writer.write(CsvFormat.COMMENT_LINE)
        writer.write(CsvFormat.CRLF)

        // Line 2: Header row
        writer.write(CsvFormat.HEADER_LINE)
        writer.write(CsvFormat.CRLF)

        // Data rows
        for (solve in solves) {
            val sessionId = solve.sessionId ?: ""
            val sessionName = sessionNameLookup(solve.sessionId)
            val puzzle = CubeTypeConverters.fromMode(solve.mode)
            val penaltyStr = CsvFormat.formatPenalty(solve.penalty)

            val row = CsvFormat.formatRow(
                listOf(
                    solve.id,
                    sessionId,
                    sessionName,
                    puzzle,
                    solve.timestamp.toString(),
                    solve.timeInMillis.toString(),
                    penaltyStr,
                    solve.scramble
                )
            )
            writer.write(row)
        }

        writer.flush()
    }

    /**
     * Convenience helper to export solves directly to an in-memory UTF-8 CSV string.
     */
    fun exportSolvesToString(
        solves: List<SolveTime>,
        sessionNameLookup: (String?) -> String
    ): String {
        val baos = ByteArrayOutputStream()
        exportSolves(baos, solves, sessionNameLookup)
        return baos.toString(StandardCharsets.UTF_8.name())
    }
}
