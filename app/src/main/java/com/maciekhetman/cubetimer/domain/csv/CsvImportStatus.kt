package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.TimingDevice

/**
 * Decoded solve record representing one CSV row.
 */
data class CsvSolveRecord(
    val solveId: String,
    val sessionId: String,
    val sessionName: String,
    val puzzle: Mode,
    val timestamp: Long,
    val time: Long,
    val penalty: Penalty,
    val scramble: String,
    /** Null when the file has no `timing_device` column, so a restored row keeps the device it already had. */
    val timingDevice: TimingDevice? = null
)

/**
 * Status and reporting outcome of a CSV import operation.
 */
sealed interface CsvImportStatus {
    data class Success(
        val importedCount: Int,
        val duplicateCount: Int,
        val malformedCount: Int,
        val sessionsCreatedCount: Int
    ) : CsvImportStatus

    /**
     * [reason] describes the problem in English (logs, tests); [problem] and [missingColumns] let the UI
     * word it in the user's language.
     */
    data class InvalidFile(
        val reason: String,
        val problem: Problem = Problem.OTHER,
        val missingColumns: List<String> = emptyList()
    ) : CsvImportStatus {
        enum class Problem { MISSING_HEADER_ROW, INVALID_COMMENT, MISSING_SOURCE_COMMENT, MISSING_COLUMNS, OTHER }
    }
    data object EmptyFile : CsvImportStatus
    data class Error(val throwable: Throwable) : CsvImportStatus
}
