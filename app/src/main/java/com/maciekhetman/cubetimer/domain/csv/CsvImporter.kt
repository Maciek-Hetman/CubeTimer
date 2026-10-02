package com.maciekhetman.cubetimer.domain.csv

import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.dao.getSolvesByIdsChunked
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.TimingDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
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
                    next ?: return@withContext CsvImportStatus.InvalidFile(
                        "CSV file missing column header row.",
                        CsvImportStatus.InvalidFile.Problem.MISSING_HEADER_ROW
                    )
                } else if (isOtherComment) {
                    return@withContext CsvImportStatus.InvalidFile(
                        "Invalid comment header. Expected '# Source: CubeTimer'.",
                        CsvImportStatus.InvalidFile.Problem.INVALID_COMMENT
                    )
                } else {
                    // Tolerant fallback: Check if firstRecord itself is the header row
                    val candidateCols = firstRecord.map { it.trim().lowercase() }.toSet()
                    if (CsvFormat.REQUIRED_COLUMNS.all { it in candidateCols }) {
                        firstRecord
                    } else {
                        return@withContext CsvImportStatus.InvalidFile(
                            "File missing required '# Source: CubeTimer' comment header.",
                            CsvImportStatus.InvalidFile.Problem.MISSING_SOURCE_COMMENT
                        )
                    }
                }

                val colMap = headerRecord.mapIndexed { idx, name -> name.trim().lowercase() to idx }.toMap()
                val missingCols = CsvFormat.REQUIRED_COLUMNS.filter { it !in colMap }
                if (missingCols.isNotEmpty()) {
                    return@withContext CsvImportStatus.InvalidFile(
                        "Missing required column(s): ${missingCols.joinToString()}",
                        CsvImportStatus.InvalidFile.Problem.MISSING_COLUMNS,
                        missingCols
                    )
                }

                // Indices
                val solveIdIdx = colMap.getValue("solve_id")
                val sessionIdIdx = colMap.getValue("session_id")
                val sessionNameIdx = colMap.getValue("session_name")
                val puzzleIdx = colMap.getValue("puzzle")
                val timestampIdx = colMap.getValue("timestamp")
                val timeIdx = colMap.getValue("time")
                val penaltyIdx = colMap.getValue("penalty")
                val scrambleIdx = colMap.getValue("scramble")
                val timingDeviceIdx = colMap[CsvFormat.TIMING_DEVICE_COLUMN]

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

                    val rawSolveId = CsvFormat.unescapeFormula(row[solveIdIdx].trim())
                    val rawSessionId = CsvFormat.unescapeFormula(row[sessionIdIdx].trim())
                    val sessionName = CsvFormat.unescapeFormula(row[sessionNameIdx].trim())
                    val rawPuzzle = row[puzzleIdx].trim()
                    val rawTimestamp = row[timestampIdx].trim()
                    val rawTime = row[timeIdx].trim()
                    val rawPenalty = row[penaltyIdx].trim()
                    val scramble = CsvFormat.unescapeFormula(row[scrambleIdx])
                    val timingDevice = timingDeviceIdx?.let { row.getOrNull(it) }?.let { TimingDevice.fromString(it) }

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
                            scramble = scramble,
                            timingDevice = timingDevice
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

                // 4-6. Reconcile with the database and write, all in one transaction: the restored rows
                // are copies of what was read, so a sync landing between a read and the write would
                // otherwise be overwritten with the old row and its outbox mutation would carry a stale
                // base version.
                val status = database.withTransaction {
                    // 4. Reconcile with existing solves (chunked by 500). solves.id is the table's only
                    // primary key, so an id held by another owner can be neither inserted (REPLACE would
                    // overwrite that account's row) nor restored: it counts as a duplicate. A live row of
                    // this owner is a duplicate too; a soft-deleted row of this owner is brought back.
                    val existingById = solveDao.getSolvesByIdsChunked(validRecords.map { it.solveId }).associateBy { it.id }
                    val solvesToInsert = mutableListOf<CsvSolveRecord>()
                    val solvesToRestore = mutableListOf<Pair<CsvSolveRecord, SolveEntity>>()
                    for (record in validRecords) {
                        val existing = existingById[record.solveId]
                        when {
                            existing == null -> solvesToInsert += record
                            existing.ownerId == ownerId && existing.deletedAt != null -> solvesToRestore += record to existing
                            else -> duplicateCount++
                        }
                    }

                    if (solvesToInsert.isEmpty() && solvesToRestore.isEmpty()) {
                        return@withTransaction CsvImportStatus.Success(
                            importedCount = 0,
                            duplicateCount = duplicateCount,
                            malformedCount = malformedCount,
                            sessionsCreatedCount = 0
                        )
                    }

                    // 5. Check & Auto-Recreate Missing Sessions (chunked by 500). A session of this owner
                    // that was soft-deleted is restored, so imported solves don't land in a session
                    // History hides. sessions.id is global, so a row owned by someone else cannot be
                    // reused: those solves are filed under a new session instead of attaching to it.
                    val referencedSessionIds = (solvesToInsert + solvesToRestore.map { it.first })
                        .map { it.sessionId }
                        .distinct()
                    val existingSessionsById = mutableMapOf<String, SessionEntity>()
                    referencedSessionIds.chunked(500).forEach { chunk ->
                        sessionDao.getSessionsByIds(chunk).forEach { existingSessionsById[it.id] = it }
                    }
                    val sessionIdRewrites = existingSessionsById
                        .filterValues { it.ownerId != ownerId }
                        .mapValues { UUID.randomUUID().toString() }

                    fun CsvSolveRecord.withOwnedSession(): CsvSolveRecord {
                        val rewritten = sessionIdRewrites[sessionId] ?: return this
                        return copy(sessionId = rewritten)
                    }

                    val recordsToInsert = solvesToInsert.map { it.withOwnedSession() }
                    val recordsToRestore = solvesToRestore.map { (record, existing) ->
                        record.withOwnedSession() to existing
                    }
                    val importedRecords = recordsToInsert + recordsToRestore.map { it.first }
                    val ownedSessionIds = importedRecords.map { it.sessionId }.distinct()
                    val missingSessionIds = ownedSessionIds.filter { id ->
                        val existing = existingSessionsById[id]
                        existing == null || existing.ownerId != ownerId
                    }
                    val nowIso = CubeTypeConverters.nowIso()

                    // Grouped once up front; filtering the whole import per missing session was
                    // O(sessions x solves). The name comes from the earliest solve, not file order,
                    // so a merged export with disagreeing session_name cells stays stable.
                    val solvesBySessionId = importedRecords.groupBy { it.sessionId }
                    val sessionsToCreate = missingSessionIds.map { sId ->
                        val sessionSolves = solvesBySessionId.getValue(sId)
                        val earliestSolve = sessionSolves.minBy { it.timestamp }
                        val maxTimestamp = sessionSolves.maxOf { it.timestamp }

                        SessionEntity(
                            id = sId,
                            ownerId = ownerId,
                            name = earliestSolve.sessionName.ifBlank { "Imported Session" },
                            event = CubeTypeConverters.fromMode(earliestSolve.puzzle),
                            // Imported sessions are closed (ended_at = last solve), so the automatic
                            // session policy never picks one up as the open session to append to.
                            kind = SessionKind.AUTOMATIC.value,
                            startedAt = CubeTypeConverters.epochMillisToIso(earliestSolve.timestamp),
                            endedAt = CubeTypeConverters.epochMillisToIso(maxTimestamp),
                            archived = false,
                            version = 0L,
                            updatedAt = nowIso,
                            deletedAt = null
                        )
                    }
                    val sessionsToRestore = existingSessionsById.values
                        .filter { it.ownerId == ownerId && it.deletedAt != null }
                        .map { it.copy(deletedAt = null, updatedAt = nowIso) }

                    val solveEntitiesToInsert = recordsToInsert.map { record ->
                        SolveEntity(
                            id = record.solveId,
                            ownerId = ownerId,
                            sessionId = record.sessionId,
                            event = CubeTypeConverters.fromMode(record.puzzle),
                            durationMs = record.time,
                            penalty = CubeTypeConverters.fromPenalty(record.penalty),
                            solvedAt = CubeTypeConverters.epochMillisToIso(record.timestamp),
                            scramble = record.scramble,
                            version = 0L,
                            updatedAt = CubeTypeConverters.epochMillisToIso(record.timestamp),
                            deletedAt = null,
                            timingDevice = (record.timingDevice ?: TimingDevice.KEYBOARD).value
                        )
                    }
                    // The server version is kept, so sync doesn't send a stale base version for a row it knows.
                    val solveEntitiesToRestore = recordsToRestore.map { (record, existing) ->
                        existing.copy(
                            sessionId = record.sessionId,
                            event = CubeTypeConverters.fromMode(record.puzzle),
                            durationMs = record.time,
                            penalty = CubeTypeConverters.fromPenalty(record.penalty),
                            solvedAt = CubeTypeConverters.epochMillisToIso(record.timestamp),
                            scramble = record.scramble,
                            timingDevice = record.timingDevice?.value ?: existing.timingDevice,
                            deletedAt = null,
                            updatedAt = nowIso
                        )
                    }

                    // 6. Write to Room & Sync Outbox
                    if (sessionsToCreate.isNotEmpty()) {
                        sessionDao.insertAll(sessionsToCreate)
                    }
                    if (sessionsToRestore.isNotEmpty()) {
                        sessionDao.upsertAll(sessionsToRestore)
                    }
                    if (ownerId != "guest") {
                        val sessionMutations = (sessionsToCreate + sessionsToRestore)
                            .map { it.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json) }
                        if (sessionMutations.isNotEmpty()) syncOutboxDao.enqueueAll(sessionMutations)
                    }

                    if (solveEntitiesToInsert.isNotEmpty()) {
                        solveDao.insertAll(solveEntitiesToInsert)
                    }
                    if (solveEntitiesToRestore.isNotEmpty()) {
                        solveDao.upsertAll(solveEntitiesToRestore)
                    }
                    if (ownerId != "guest") {
                        syncOutboxDao.enqueueAll(
                            (solveEntitiesToInsert + solveEntitiesToRestore)
                                .map { it.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json) }
                        )
                    }

                    CsvImportStatus.Success(
                        importedCount = solveEntitiesToInsert.size + solveEntitiesToRestore.size,
                        duplicateCount = duplicateCount,
                        malformedCount = malformedCount,
                        sessionsCreatedCount = sessionsToCreate.size
                    )
                }

                if (status.importedCount > 0) {
                    syncTrigger?.invoke()
                }
                status
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            CsvImportStatus.Error(t)
        }
    }
}
