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
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
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
    ): ConflictEntity = inCurrentTransactionOrIo {
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

    override suspend fun resolveConflict(conflictId: String, policy: ConflictPolicy): Boolean = inCurrentTransactionOrIo {
        val conflict = conflictDao.getConflictById(conflictId) ?: return@inCurrentTransactionOrIo false
        if (conflict.resolved) return@inCurrentTransactionOrIo true

        when (policy) {
            ConflictPolicy.SERVER_WINS -> applyServerWins(conflict)
            ConflictPolicy.LOCAL_WINS -> applyLocalWins(conflict)
        }
    }

    override suspend fun resolveKeepServer(conflictId: String): Boolean =
        resolveConflict(conflictId, ConflictPolicy.SERVER_WINS)

    override suspend fun resolveKeepLocal(conflictId: String): Boolean =
        resolveConflict(conflictId, ConflictPolicy.LOCAL_WINS)

    /**
     * Keep server: write the conflict's server snapshot over the local row - unless the row has
     * already moved past it. The sync engine applies the same response's `changes` right after
     * recording a conflict, so newer server versions of the entity can land on the row after the
     * snapshot was taken; the cursor has passed them, so they would never come back if the stale
     * snapshot overwrote them. In that case the row already holds the server's (newer) state and
     * the conflict is simply marked resolved.
     */
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
            val snapshotVersion = conflict.serverVersion.coerceAtLeast(dto?.version ?: 0L)
            val localVersion = sessionDao.getSessionById(conflict.entityId)?.version

            if (localVersion != null && localVersion > snapshotVersion) {
                // Row is newer than the snapshot: leave it alone.
            } else if (dto != null) {
                val entity = dto.toEntity(
                    ownerId = conflict.ownerId,
                    version = conflict.serverVersion.coerceAtLeast(dto.version),
                    updatedAt = dto.updatedAt ?: serverUpdated
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
            val snapshotVersion = conflict.serverVersion.coerceAtLeast(dto?.version ?: 0L)
            val localVersion = solveDao.getSolveById(conflict.entityId)?.version

            if (localVersion != null && localVersion > snapshotVersion) {
                // Row is newer than the snapshot: leave it alone.
            } else if (dto != null) {
                val entity = dto.toEntity(
                    ownerId = conflict.ownerId,
                    version = conflict.serverVersion.coerceAtLeast(dto.version),
                    updatedAt = dto.updatedAt ?: serverUpdated
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

    /**
     * Keep local: re-assert the user's version of the entity - the conflicting mutation's payload
     * stored on the conflict ([ConflictEntity.localPayloadJson], what the conflict UI shows as
     * "This device") - not the current Room row. The sync engine deletes the conflicting outbox
     * mutation when it records the conflict and then applies the same response's `changes`, which
     * usually carry the server's newer copy of this very entity; with nothing queued any more, that
     * copy overwrites the row. Re-sending the row would therefore push the server's data back.
     *
     * The re-asserted version is written back to Room (so the UI shows what was kept) and queued as
     * an upsert (or a delete, when the conflicting mutation was one - it has no payload) whose base
     * version is the newest server version known locally: the conflict's, or a newer one that has
     * since reached the row. The row's `version` is set to that same value.
     *
     * If the entity already has a queued mutation, the user edited it again after the conflicting
     * edit (queued mutations older than it were settled with its outcome); that newer edit is
     * what should win, so it is only rebased onto the newest known server version rather than
     * clobbered with the older conflict payload.
     *
     * A payload that cannot be decoded falls back to re-sending the current row.
     */
    private suspend fun applyLocalWins(conflict: ConflictEntity): Boolean = database.withTransaction {
        val nowIso = CubeTypeConverters.nowIso()

        if (conflict.entityType == "session") {
            keepLocalSession(conflict, nowIso)
        } else if (conflict.entityType == "solve") {
            keepLocalSolve(conflict, nowIso)
        }

        conflictDao.resolveConflict(conflict.conflictId, nowIso)
        true
    }

    private suspend fun keepLocalSession(conflict: ConflictEntity, nowIso: String) {
        val localSession = sessionDao.getSessionById(conflict.entityId)
        val knownServerVersion = conflict.serverVersion.coerceAtLeast(localSession?.version ?: 0L)

        if (rebaseQueuedMutation(conflict, knownServerVersion)) {
            if (localSession != null && localSession.version < knownServerVersion) {
                sessionDao.update(localSession.copy(version = knownServerVersion))
            }
            return
        }

        val localPayloadJson = conflict.localPayloadJson
        if (localPayloadJson == null) {
            // The conflicting mutation was a delete: soft-delete locally and re-send it.
            if (localSession != null) {
                sessionDao.update(
                    localSession.copy(
                        version = knownServerVersion,
                        deletedAt = localSession.deletedAt ?: nowIso,
                        updatedAt = nowIso
                    )
                )
            }
            syncOutboxDao.enqueue(
                sessionDeleteMutation(
                    entityId = conflict.entityId,
                    ownerId = conflict.ownerId,
                    baseVersion = knownServerVersion,
                    clientTime = nowIso
                )
            )
            return
        }

        val payload = try {
            json.decodeFromString<SessionSyncPayload>(localPayloadJson)
        } catch (_: Exception) {
            null
        }
        if (payload == null) {
            // Undecodable payload: fall back to re-sending the current row.
            if (localSession != null && localSession.deletedAt == null) {
                val rebased = localSession.copy(version = knownServerVersion)
                sessionDao.update(rebased)
                syncOutboxDao.enqueue(rebased.toUpsertMutation(ownerId = conflict.ownerId, clientTime = nowIso, json = json))
            } else {
                syncOutboxDao.enqueue(
                    sessionDeleteMutation(
                        entityId = conflict.entityId,
                        ownerId = conflict.ownerId,
                        baseVersion = knownServerVersion,
                        clientTime = nowIso
                    )
                )
            }
            return
        }

        val kept = SessionEntity(
            id = conflict.entityId,
            ownerId = conflict.ownerId,
            name = payload.name,
            event = payload.event,
            kind = payload.kind,
            startedAt = payload.startedAt,
            endedAt = payload.endedAt,
            archived = payload.archived,
            version = knownServerVersion,
            updatedAt = nowIso,
            deletedAt = null
        )
        // upsert (UPDATE in place), never a REPLACE: deleting the session row would fire the
        // solves.session_id ON DELETE SET NULL and detach every solve in it.
        sessionDao.upsert(kept)
        syncOutboxDao.enqueue(kept.toUpsertMutation(ownerId = conflict.ownerId, clientTime = nowIso, json = json))
    }

    private suspend fun keepLocalSolve(conflict: ConflictEntity, nowIso: String) {
        val localSolve = solveDao.getSolveById(conflict.entityId)
        val knownServerVersion = conflict.serverVersion.coerceAtLeast(localSolve?.version ?: 0L)

        if (rebaseQueuedMutation(conflict, knownServerVersion)) {
            if (localSolve != null && localSolve.version < knownServerVersion) {
                solveDao.update(localSolve.copy(version = knownServerVersion))
            }
            return
        }

        val localPayloadJson = conflict.localPayloadJson
        if (localPayloadJson == null) {
            // The conflicting mutation was a delete: soft-delete locally and re-send it.
            if (localSolve != null) {
                solveDao.update(
                    localSolve.copy(
                        version = knownServerVersion,
                        deletedAt = localSolve.deletedAt ?: nowIso,
                        updatedAt = nowIso
                    )
                )
            }
            syncOutboxDao.enqueue(
                solveDeleteMutation(
                    entityId = conflict.entityId,
                    ownerId = conflict.ownerId,
                    baseVersion = knownServerVersion,
                    clientTime = nowIso
                )
            )
            return
        }

        val payload = try {
            json.decodeFromString<SolveSyncPayload>(localPayloadJson)
        } catch (_: Exception) {
            null
        }
        if (payload == null) {
            // Undecodable payload: fall back to re-sending the current row.
            if (localSolve != null && localSolve.deletedAt == null) {
                val rebased = localSolve.copy(version = knownServerVersion)
                solveDao.update(rebased)
                syncOutboxDao.enqueue(rebased.toUpsertMutation(ownerId = conflict.ownerId, clientTime = nowIso, json = json))
            } else {
                syncOutboxDao.enqueue(
                    solveDeleteMutation(
                        entityId = conflict.entityId,
                        ownerId = conflict.ownerId,
                        baseVersion = knownServerVersion,
                        clientTime = nowIso
                    )
                )
            }
            return
        }

        val kept = SolveEntity(
            id = conflict.entityId,
            ownerId = conflict.ownerId,
            sessionId = keptSolveSessionId(payload.sessionId, localSolve),
            event = payload.event,
            durationMs = payload.durationMs,
            penalty = payload.penalty,
            solvedAt = payload.solvedAt,
            scramble = payload.scramble,
            version = knownServerVersion,
            updatedAt = nowIso,
            deletedAt = null,
            timingDevice = payload.timingDevice
        )
        solveDao.upsert(kept)
        // Built from the row just written, so the pushed session_id always matches Room's.
        syncOutboxDao.enqueue(kept.toUpsertMutation(ownerId = conflict.ownerId, clientTime = nowIso, json = json))
    }

    /**
     * The session a kept solve is written to. `solves.session_id` is a DEFERRED foreign key, so a
     * payload naming a session that no longer exists locally (e.g. hard-deleted since) would not
     * fail on the write but on commit, aborting the whole resolution. Such a payload keeps the
     * session the row currently references (it committed, so it exists), or none. A soft-deleted
     * session still exists as a row and is kept as is.
     */
    private suspend fun keptSolveSessionId(payloadSessionId: String?, localSolve: SolveEntity?): String? {
        if (payloadSessionId == null || sessionDao.getSessionById(payloadSessionId) != null) {
            return payloadSessionId
        }
        return localSolve?.sessionId?.takeIf { sessionDao.getSessionById(it) != null }
    }

    /**
     * Runs [block] on [ioDispatcher], unless this thread is already inside a Room transaction.
     * Switching dispatchers there writes through a different connection: the row can commit
     * even when the caller's transaction rolls back, and the other connection can deadlock
     * waiting for the lock the transaction still holds.
     */
    private suspend fun <T> inCurrentTransactionOrIo(block: suspend () -> T): T =
        if (database.inTransaction()) block() else withContext(ioDispatcher) { block() }

    /**
     * If the entity already has a queued mutation (a newer local edit), rebases the newest one onto
     * [knownServerVersion] - the sync engine sends only the newest per entity, with the highest base
     * version of the group - and returns true. Returns false when nothing is queued.
     */
    private suspend fun rebaseQueuedMutation(conflict: ConflictEntity, knownServerVersion: Long): Boolean {
        val queued = syncOutboxDao.getPendingMutationForEntity(
            ownerId = conflict.ownerId,
            entityType = conflict.entityType,
            entityId = conflict.entityId
        ) ?: return false
        if (queued.baseVersion < knownServerVersion) {
            syncOutboxDao.update(queued.copy(baseVersion = knownServerVersion))
        }
        return true
    }
}
