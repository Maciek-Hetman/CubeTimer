package com.maciekhetman.cubetimer.domain.csv

import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class CsvImporter(
    private val database: CubeDatabase,
    private val solveDao: SolveDao = database.solveDao(),
    private val sessionDao: SessionDao = database.sessionDao(),
    private val syncOutboxDao: SyncOutboxDao = database.syncOutboxDao(),
    private val json: Json = NetworkModule.json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syncTrigger: (() -> Unit)? = null
) {

    suspend fun importCsv(
        inputStream: InputStream,
        ownerId: String = "guest"
    ): CsvImportStatus = withContext(ioDispatcher) {
        try {
            CsvRecordReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                // 1. Read first record (skipping leading blank lines if any)
                var firstRecord = reader.readNextRecord()
                while (firstRecord != null && (firstRecord.isEmpty() || (firstRecord.size == 1 && firstRecord[0].isBlank()))) {
                    firstRecord = reader.readNextRecord()
                }
                if (firstRecord == null) {
                    return@withContext CsvImportStatus.EmptyFile
                }

                // 2. Validate comment line or match header directly
                val firstField = firstRecord.firstOrNull()?.trim() ?: ""
                val hasComment = firstField.startsWith("# Source: CubeTimer", ignoreCase = true)
                val isOtherComment = firstRecord.size == 1 && firstField.startsWith("#")

                val headerRecord: List<String> = if (hasComment) {
                    var next = reader.readNextRecord()
                    while (next != null && (next.isEmpty() || (next.size == 1 && next[0].isBlank()))) {
                        next = reader.readNextRecord()
                    }
                    next ?: return@withContext CsvImportStatus.InvalidFile("CSV file missing column header row.")
                } else if (isOtherComment) {
                    return@withContext CsvImportStatus.InvalidFile("Invalid comment header. Expected '# Source: CubeTimer'.")
                } else {
                    // Tolerant fallback: Check if firstRecord itself is the header row
                    val candidateCols = firstRecord.map { it.trim().lowercase() }.toSet()
                    val requiredCols = listOf(
                        "solve_id", "session_id", "session_name", "puzzle",
                        "timestamp", "time", "penalty", "scramble"
                    )
                    if (requiredCols.all { it in candidateCols }) {
                        firstRecord
                    } else {
                        return@withContext CsvImportStatus.InvalidFile("File missing required '# Source: CubeTimer' comment header.")
                    }
                }

                val colMap = headerRecord.mapIndexed { idx, name -> name.trim().lowercase() to idx }.toMap()
                val requiredCols = listOf(
                    "solve_id", "session_id", "session_name", "puzzle",
                    "timestamp", "time", "penalty", "scramble"
                )
                val missingCols = requiredCols.filter { it !in colMap }
                if (missingCols.isNotEmpty()) {
                    return@withContext CsvImportStatus.InvalidFile("Missing required column(s): ${missingCols.joinToString()}")
                }

                // Indices
                val solveIdIdx = colMap["solve_id"]!!
                val sessionIdIdx = colMap["session_id"]!!
                val sessionNameIdx = colMap["session_name"]!!
                val puzzleIdx = colMap["puzzle"]!!
                val timestampIdx = colMap["timestamp"]!!
                val timeIdx = colMap["time"]!!
                val penaltyIdx = colMap["penalty"]!!
                val scrambleIdx = colMap["scramble"]!!

                val maxIdx = maxOf(
                    solveIdIdx, sessionIdIdx, sessionNameIdx, puzzleIdx,
                    timestampIdx, timeIdx, penaltyIdx, scrambleIdx
                )

                // 3. Parse Data Rows
                val validRecords = mutableListOf<CsvSolveRecord>()
                var malformedCount = 0
                val seenSolveIdsInFile = mutableSetOf<String>()
                var duplicateCount = 0
                val fallbackSessionId by lazy { UUID.randomUUID().toString() }

                while (true) {
                    val row = reader.readNextRecord() ?: break
                    if (row.isEmpty() || (row.size == 1 && row[0].isBlank())) {
                        continue
                    }
                    if (row.size <= maxIdx) {
                        malformedCount++
                        continue
                    }

                    val rawSolveId = row[solveIdIdx].trim()
                    val rawSessionId = row[sessionIdIdx].trim()
                    val sessionName = row[sessionNameIdx].trim()
                    val rawPuzzle = row[puzzleIdx].trim()
                    val rawTimestamp = row[timestampIdx].trim()
                    val rawTime = row[timeIdx].trim()
                    val rawPenalty = row[penaltyIdx].trim()
                    val scramble = row[scrambleIdx]

                    val timestamp = rawTimestamp.toLongOrNull()
                    val time = rawTime.toLongOrNull()

                    if (rawSolveId.isBlank() || timestamp == null || timestamp <= 0 || time == null || time < 0) {
                        malformedCount++
                        continue
                    }

                    if (!seenSolveIdsInFile.add(rawSolveId)) {
                        duplicateCount++
                        continue
                    }

                    val penalty = CsvFormat.parsePenalty(rawPenalty)
                    val mode = CubeTypeConverters.toMode(rawPuzzle)

                    validRecords.add(
                        CsvSolveRecord(
                            solveId = rawSolveId,
                            sessionId = rawSessionId.ifBlank { fallbackSessionId },
                            sessionName = sessionName.ifBlank { "Imported Session" },
                            puzzle = mode,
                            timestamp = timestamp,
                            time = time,
                            penalty = penalty,
                            scramble = scramble
                        )
                    )
                }

                if (validRecords.isEmpty()) {
                    return@withContext CsvImportStatus.Success(
                        importedCount = 0,
                        duplicateCount = duplicateCount,
                        malformedCount = malformedCount,
                        sessionsCreatedCount = 0
                    )
                }

                // 4. Check DB Duplicates (chunked by 500)
                val candidateSolveIds = validRecords.map { it.solveId }
                val existingDbSolveIds = mutableSetOf<String>()
                candidateSolveIds.chunked(500).forEach { chunk ->
                    val existing = solveDao.getExistingSolveIds(chunk)
                    existingDbSolveIds.addAll(existing)
                }

                val solvesToInsert = validRecords.filter { it.solveId !in existingDbSolveIds }
                val dbDuplicates = validRecords.size - solvesToInsert.size
                duplicateCount += dbDuplicates

                if (solvesToInsert.isEmpty()) {
                    return@withContext CsvImportStatus.Success(
                        importedCount = 0,
                        duplicateCount = duplicateCount,
                        malformedCount = malformedCount,
                        sessionsCreatedCount = 0
                    )
                }

                // 5. Check & Auto-Recreate Missing Sessions (chunked by 500)
                val referencedSessionIds = solvesToInsert.map { it.sessionId }.distinct()
                val existingDbSessionIds = mutableSetOf<String>()
                referencedSessionIds.chunked(500).forEach { chunk ->
                    val existing = sessionDao.getSessionsByIds(chunk)
                    existingDbSessionIds.addAll(existing.map { it.id })
                }

                val missingSessionIds = referencedSessionIds.filter { it !in existingDbSessionIds }
                val nowIso = CubeTypeConverters.nowIso()

                val sessionsToCreate = missingSessionIds.map { sId ->
                    val sessionSolves = solvesToInsert.filter { it.sessionId == sId }
                    val firstSolve = sessionSolves.first()
                    val minTimestamp = sessionSolves.minOf { it.timestamp }
                    val maxTimestamp = sessionSolves.maxOf { it.timestamp }

                    SessionEntity(
                        id = sId,
                        ownerId = ownerId,
                        name = firstSolve.sessionName.ifBlank { "Imported Session" },
                        event = CubeTypeConverters.fromMode(firstSolve.puzzle),
                        kind = SessionKind.MANUAL.value,
                        startedAt = CubeTypeConverters.epochMillisToIso(minTimestamp),
                        endedAt = CubeTypeConverters.epochMillisToIso(maxTimestamp),
                        archived = false,
                        version = 0L,
                        updatedAt = nowIso,
                        deletedAt = null
                    )
                }

                val solveEntitiesToInsert = solvesToInsert.map { record ->
                    val iso = CubeTypeConverters.epochMillisToIso(record.timestamp)
                    val penaltyStr = when (record.penalty) {
                        Penalty.NONE -> "none"
                        Penalty.PLUS_TWO -> "plus_two"
                        Penalty.DNF -> "dnf"
                    }
                    SolveEntity(
                        id = record.solveId,
                        ownerId = ownerId,
                        sessionId = record.sessionId,
                        event = CubeTypeConverters.fromMode(record.puzzle),
                        durationMs = record.time,
                        penalty = penaltyStr,
                        solvedAt = iso,
                        scramble = record.scramble,
                        version = 0L,
                        updatedAt = iso,
                        deletedAt = null
                    )
                }

                // 6. Transactional Write to Room & Sync Outbox
                database.withTransaction {
                    if (sessionsToCreate.isNotEmpty()) {
                        sessionDao.insertAll(sessionsToCreate)
                        if (ownerId != "guest") {
                            syncOutboxDao.enqueueAll(
                                sessionsToCreate.map { it.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json) }
                            )
                        }
                    }

                    solveDao.insertAll(solveEntitiesToInsert)
                    if (ownerId != "guest") {
                        syncOutboxDao.enqueueAll(
                            solveEntitiesToInsert.map { it.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json) }
                        )
                    }
                }

                syncTrigger?.invoke()

                CsvImportStatus.Success(
                    importedCount = solveEntitiesToInsert.size,
                    duplicateCount = duplicateCount,
                    malformedCount = malformedCount,
                    sessionsCreatedCount = sessionsToCreate.size
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CsvImportStatus.Error(t)
        }
    }
}
