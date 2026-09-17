package com.maciekhetman.cubetimer.domain.csv

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty

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
    val scramble: String
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
    ) : CsvImportStatus {
        val hasImports: Boolean get() = importedCount > 0
        val totalProcessed: Int get() = importedCount + duplicateCount + malformedCount
    }

    data class InvalidFile(val reason: String) : CsvImportStatus
    data object EmptyFile : CsvImportStatus
    data class Error(val throwable: Throwable) : CsvImportStatus
}
