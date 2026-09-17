package com.maciekhetman.cubetimer.data.sync

import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.sessionDeleteMutation
import com.maciekhetman.cubetimer.data.local.mapper.solveDeleteMutation
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class ConflictResolverImpl(
    private val database: CubeDatabase,
    private val conflictDao: ConflictDao = database.conflictDao(),
    private val solveDao: SolveDao = database.solveDao(),
    private val sessionDao: SessionDao = database.sessionDao(),
    private val syncOutboxDao: SyncOutboxDao = database.syncOutboxDao(),
    private val json: Json = NetworkModule.json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ConflictResolver {

    override fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>> {
        return conflictDao.observeUnresolvedConflicts(ownerId)
    }

    override fun observeUnresolvedCount(ownerId: String): Flow<Int> {
        return conflictDao.observeUnresolvedCount(ownerId)
    }

    override suspend fun getConflictById(conflictId: String): ConflictEntity? = withContext(ioDispatcher) {
        conflictDao.getConflictById(conflictId)
    }

    override suspend fun recordConflict(
        ownerId: String,
        mutationId: String,
        entityType: String,
        entityId: String,
        serverVersion: Long,
        serverUpdatedAt: String?,
        localPayloadJson: String?,
        serverPayloadJson: String?,
        errorMessage: String
    ): ConflictEntity = withContext(ioDispatcher) {
        val nowIso = CubeTypeConverters.nowIso()
        val conflict = ConflictEntity(
            conflictId = UUID.randomUUID().toString(),
            ownerId = ownerId,
            mutationId = mutationId,
            entityType = entityType,
            entityId = entityId,
            serverVersion = serverVersion,
            serverUpdatedAt = serverUpdatedAt,
            localPayloadJson = localPayloadJson,
            serverPayloadJson = serverPayloadJson,
            errorMessage = errorMessage,
            createdAt = nowIso,
            resolved = false,
            resolvedAt = null
        )
        conflictDao.insert(conflict)
        conflict
    }

    override suspend fun resolveConflict(conflictId: String, policy: ConflictPolicy): Boolean = withContext(ioDispatcher) {
        val conflict = conflictDao.getConflictById(conflictId) ?: return@withContext false
        if (conflict.resolved) return@withContext true

        val effectivePolicy = if (policy == ConflictPolicy.LAST_WRITE_WINS) {
            determineLwwPolicy(conflict)
        } else {
            policy
        }

        when (effectivePolicy) {
            ConflictPolicy.MANUAL_PROMPT -> true
            ConflictPolicy.SERVER_WINS -> applyServerWins(conflict)
            ConflictPolicy.LOCAL_WINS -> applyLocalWins(conflict)
            ConflictPolicy.LAST_WRITE_WINS -> applyServerWins(conflict) // Fallback handled above
        }
    }

    override suspend fun resolveKeepServer(conflictId: String): Boolean =
        resolveConflict(conflictId, ConflictPolicy.SERVER_WINS)

    override suspend fun resolveKeepLocal(conflictId: String): Boolean =
        resolveConflict(conflictId, ConflictPolicy.LOCAL_WINS)

    private suspend fun determineLwwPolicy(conflict: ConflictEntity): ConflictPolicy {
        val localTimeMs = extractLocalTimestamp(conflict)
        val serverTimeMs = extractServerTimestamp(conflict)
        return if (localTimeMs > serverTimeMs) ConflictPolicy.LOCAL_WINS else ConflictPolicy.SERVER_WINS
    }

    private suspend fun extractLocalTimestamp(conflict: ConflictEntity): Long {
        if (conflict.entityType == "solve") {
            val solve = solveDao.getSolveById(conflict.entityId)
            if (solve != null) return CubeTypeConverters.isoToEpochMillis(solve.updatedAt)
        } else if (conflict.entityType == "session") {
            val session = sessionDao.getSessionById(conflict.entityId)
            if (session != null) return CubeTypeConverters.isoToEpochMillis(session.updatedAt)
        }
        return conflict.localPayloadJson?.let {
            try {
                if (conflict.entityType == "solve") {
                    val p = json.decodeFromString<SolveSyncPayload>(it)
                    CubeTypeConverters.isoToEpochMillis(p.solvedAt)
                } else {
                    val p = json.decodeFromString<SessionSyncPayload>(it)
                    CubeTypeConverters.isoToEpochMillis(p.startedAt)
                }
            } catch (_: Exception) {
                0L
            }
        } ?: 0L
    }

    private fun extractServerTimestamp(conflict: ConflictEntity): Long {
        conflict.serverUpdatedAt?.let {
            val ms = CubeTypeConverters.isoToEpochMillis(it)
            if (ms > 0L) return ms
        }
        return conflict.serverPayloadJson?.let {
            try {
                if (conflict.entityType == "solve") {
                    val dto = json.decodeFromString<SolveSnapshotDto>(it)
                    dto.updatedAt?.let { u -> CubeTypeConverters.isoToEpochMillis(u) }
                        ?: CubeTypeConverters.isoToEpochMillis(dto.solvedAt)
                } else {
                    val dto = json.decodeFromString<SessionSnapshotDto>(it)
                    dto.updatedAt?.let { u -> CubeTypeConverters.isoToEpochMillis(u) }
                        ?: CubeTypeConverters.isoToEpochMillis(dto.startedAt)
                }
            } catch (_: Exception) {
                0L
            }
        } ?: 0L
    }

    private suspend fun applyServerWins(conflict: ConflictEntity): Boolean = database.withTransaction {
        val nowIso = CubeTypeConverters.nowIso()
        val serverUpdated = conflict.serverUpdatedAt ?: nowIso

        if (conflict.entityType == "session") {
            val dto = conflict.serverPayloadJson?.let {
                try {
                    json.decodeFromString<SessionSnapshotDto>(it)
                } catch (_: Exception) {
                    null
                }
            }

            if (dto != null) {
                val entity = SessionEntity(
                    id = dto.id,
                    ownerId = conflict.ownerId,
                    name = dto.name,
                    event = dto.event,
                    kind = dto.kind,
                    startedAt = dto.startedAt,
                    endedAt = dto.endedAt,
                    archived = dto.archived,
                    version = conflict.serverVersion.coerceAtLeast(dto.version),
                    updatedAt = dto.updatedAt ?: serverUpdated,
                    deletedAt = dto.deletedAt
                )
                sessionDao.upsert(entity)
            } else {
                val existing = sessionDao.getSessionById(conflict.entityId)
                if (existing != null) {
                    sessionDao.update(
                        existing.copy(
                            version = conflict.serverVersion,
                            updatedAt = serverUpdated
                        )
                    )
                }
            }
        } else if (conflict.entityType == "solve") {
            val dto = conflict.serverPayloadJson?.let {
                try {
                    json.decodeFromString<SolveSnapshotDto>(it)
                } catch (_: Exception) {
                    null
                }
            }

            if (dto != null) {
                val entity = SolveEntity(
                    id = dto.id,
                    ownerId = conflict.ownerId,
                    sessionId = dto.sessionId,
                    durationMs = dto.durationMs,
                    penalty = dto.penalty,
                    solvedAt = dto.solvedAt,
                    scramble = dto.scramble,
                    event = dto.event,
                    version = conflict.serverVersion.coerceAtLeast(dto.version),
                    updatedAt = dto.updatedAt ?: serverUpdated,
                    deletedAt = dto.deletedAt
                )
                solveDao.upsert(entity)
            } else {
                val existing = solveDao.getSolveById(conflict.entityId)
                if (existing != null) {
                    solveDao.update(
                        existing.copy(
                            version = conflict.serverVersion,
                            updatedAt = serverUpdated
                        )
                    )
                }
            }
        }

        conflictDao.resolveConflict(conflict.conflictId, nowIso)
        true
    }

    private suspend fun applyLocalWins(conflict: ConflictEntity): Boolean = database.withTransaction {
        val nowIso = CubeTypeConverters.nowIso()

        if (conflict.entityType == "session") {
            val localSession = sessionDao.getSessionById(conflict.entityId)
            if (localSession != null && localSession.deletedAt == null) {
                // baseVersion targets the server version recorded on the conflict (not the local
                // row's own version) so the retried push lands against what the server last saw.
                syncOutboxDao.enqueue(
                    localSession.copy(version = conflict.serverVersion)
                        .toUpsertMutation(ownerId = conflict.ownerId, clientTime = nowIso, json = json)
                )
            } else {
                syncOutboxDao.enqueue(
                    sessionDeleteMutation(
                        entityId = conflict.entityId,
                        ownerId = conflict.ownerId,
                        baseVersion = conflict.serverVersion,
                        clientTime = nowIso
                    )
                )
            }
        } else if (conflict.entityType == "solve") {
            val localSolve = solveDao.getSolveById(conflict.entityId)
            if (localSolve != null && localSolve.deletedAt == null) {
                // baseVersion targets the server version recorded on the conflict (not the local
                // row's own version) so the retried push lands against what the server last saw.
                syncOutboxDao.enqueue(
                    localSolve.copy(version = conflict.serverVersion)
                        .toUpsertMutation(ownerId = conflict.ownerId, clientTime = nowIso, json = json)
                )
            } else {
                syncOutboxDao.enqueue(
                    solveDeleteMutation(
                        entityId = conflict.entityId,
                        ownerId = conflict.ownerId,
                        baseVersion = conflict.serverVersion,
                        clientTime = nowIso
                    )
                )
            }
        }

        conflictDao.resolveConflict(conflict.conflictId, nowIso)
        true
    }
}
