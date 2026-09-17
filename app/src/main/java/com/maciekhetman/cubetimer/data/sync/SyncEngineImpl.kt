package com.maciekhetman.cubetimer.data.sync

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncMetadataDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.ChangeDto
import com.maciekhetman.cubetimer.data.remote.dto.DeviceDto
import com.maciekhetman.cubetimer.data.remote.dto.MutationOutcomeDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncMutationDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.time.Instant

class SyncEngineImpl(
    private val apiClient: CubeSyncApiClient,
    private val tokenStorage: TokenStorage,
    private val database: CubeDatabase,
    private val authManager: AuthManager,
    private val conflictResolver: ConflictResolver = ConflictResolverImpl(database),
    override val stateManager: SyncStateManager = SyncStateManager(),
    private val solveDao: SolveDao = database.solveDao(),
    private val sessionDao: SessionDao = database.sessionDao(),
    private val syncOutboxDao: SyncOutboxDao = database.syncOutboxDao(),
    private val syncMetadataDao: SyncMetadataDao = database.syncMetadataDao(),
    private val conflictDao: ConflictDao = database.conflictDao(),
    private val json: Json = NetworkModule.json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultConflictPolicy: ConflictPolicy = ConflictPolicy.MANUAL_PROMPT
) : SyncEngine {

    private val syncMutex = Mutex()

    override val syncStatus: StateFlow<SyncStatus> = stateManager.syncStatus
    override val lastSyncedAt: StateFlow<Long?> = stateManager.lastSyncedAt
    override val isSyncing: StateFlow<Boolean> = stateManager.isSyncing

    override fun observePendingMutationsCount(ownerId: String): Flow<Int> {
        return syncOutboxDao.observePendingCount(ownerId)
    }

    override fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>> {
        return conflictResolver.observeUnresolvedConflicts(ownerId)
    }

    override suspend fun resolveConflictKeepServer(conflictId: String): Boolean =
        conflictResolver.resolveKeepServer(conflictId)

    override suspend fun resolveConflictKeepLocal(conflictId: String): Boolean =
        conflictResolver.resolveKeepLocal(conflictId)

    override suspend fun sync(ownerId: String?): SyncResult = withContext(ioDispatcher) {
        val currentAuth = authManager.authState.value
        val resolvedOwnerId = ownerId ?: (currentAuth as? AuthState.Authenticated)?.user?.id
            ?: (currentAuth as? AuthState.Admin)?.user?.id

        // Ordering is intentional: a "guest" (or missing) resolved owner is always a silent NoOp -
        // this is the common case, hit whenever ownerId is null and currentAuth isn't
        // Authenticated/Admin (including plain Guest), or when a caller explicitly passes
        // ownerId = "guest". The AuthState.Guest -> AuthError branch below only fires for the
        // narrower, defensive case of a caller passing an explicit non-guest ownerId while the
        // actual auth state has (or raced back to) Guest - a real mismatch worth surfacing as an
        // error rather than swallowing.
        if (resolvedOwnerId == null || resolvedOwnerId == "guest" || resolvedOwnerId.isBlank()) {
            stateManager.setUnauthenticated()
            return@withContext SyncResult.NoOp
        }

        if (currentAuth is AuthState.Guest) {
            stateManager.setUnauthenticated()
            return@withContext SyncResult.AuthError("User is in guest mode")
        }

        syncMutex.withLock {
            stateManager.setSyncing()
            setSyncing(resolvedOwnerId, true)
            syncOutboxDao.resetAllInFlight(resolvedOwnerId)

            var totalMutationsSynced = 0
            var totalChangesApplied = 0
            var totalConflictsRecorded = 0
            var hasMore = true
            var loopCount = 0
            val maxLoops = 50

            try {
                while (hasMore && loopCount < maxLoops) {
                    loopCount++

                    // 1. Fetch pending outbox mutations (up to 500)
                    val pending = syncOutboxDao.getPendingMutations(resolvedOwnerId, limit = 500)
                    val pendingIds = pending.map { it.id }
                    val attemptAt = System.currentTimeMillis()

                    if (pendingIds.isNotEmpty()) {
                        syncOutboxDao.markInFlight(pendingIds, attemptAt)
                    }

                    // 2. Fetch current watermark cursor
                    val metadata = syncMetadataDao.getMetadata(resolvedOwnerId)
                    val currentCursor = metadata?.cursor ?: 0L

                    // 3. Build device metadata
                    val device = DeviceDto(
                        id = tokenStorage.getDeviceId(),
                        name = metadata?.deviceName ?: "Android Device",
                        platform = "android"
                    )

                    // 4. Map pending mutations to DTOs
                    val mutationDtos = pending.map { mutation ->
                        SyncMutationDto(
                            id = mutation.id,
                            entity = mutation.entityType,
                            entityId = mutation.entityId,
                            operation = if (mutation.action == "delete") "delete" else "upsert",
                            baseVersion = mutation.baseVersion,
                            data = mutation.payloadJson?.let {
                                try {
                                    json.parseToJsonElement(it)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        )
                    }

                    val syncRequest = SyncRequest(
                        cursor = currentCursor,
                        device = device,
                        mutations = mutationDtos,
                        limit = 500
                    )

                    // 5. Send HTTP request with 409 snapshot recovery
                    val response: SyncResponse = try {
                        apiClient.sync(syncRequest)
                    } catch (e: AuthException.CursorExpired) {
                        if (pendingIds.isNotEmpty()) {
                            syncOutboxDao.resetInFlight(pendingIds)
                        }
                        updateCursor(resolvedOwnerId, 0L, CubeTypeConverters.nowIso())
                        runSnapshotBootstrap(resolvedOwnerId)
                        continue
                    } catch (e: AuthException.ApiError) {
                        if (e.errorCode == "cursor_expired" || e.httpStatusCode == 409) {
                            if (pendingIds.isNotEmpty()) {
                                syncOutboxDao.resetInFlight(pendingIds)
                            }
                            updateCursor(resolvedOwnerId, 0L, CubeTypeConverters.nowIso())
                            runSnapshotBootstrap(resolvedOwnerId)
                            continue
                        }
                        if (pendingIds.isNotEmpty()) {
                            for (id in pendingIds) {
                                syncOutboxDao.markFailed(id, e.message, attemptAt)
                            }
                        }
                        throw e
                    } catch (e: Exception) {
                        if (pendingIds.isNotEmpty()) {
                            for (id in pendingIds) {
                                syncOutboxDao.markFailed(id, e.message, attemptAt)
                            }
                        }
                        throw e
                    }

                    // 6. Apply outcomes and changes inside database transaction
                    val batchResult = database.withTransaction {
                        applyBatch(resolvedOwnerId, pending, response)
                    }

                    totalMutationsSynced += batchResult.mutationsSynced
                    totalChangesApplied += batchResult.changesApplied
                    totalConflictsRecorded += batchResult.conflictsRecorded

                    // 7. Check if pagination loop should continue
                    val remainingPending = syncOutboxDao.countPending(resolvedOwnerId)
                    hasMore = response.hasMore || (pending.size == 500 && remainingPending > 0)
                }

                val nowEpoch = System.currentTimeMillis()
                val nowIso = CubeTypeConverters.nowIso()
                setSyncing(resolvedOwnerId, false)
                updateLastSyncTime(resolvedOwnerId, nowIso)
                stateManager.setSynced(nowEpoch)

                SyncResult.Success(
                    mutationsSynced = totalMutationsSynced,
                    changesApplied = totalChangesApplied,
                    conflictsRecorded = totalConflictsRecorded
                )
            } catch (e: AuthException.Unauthorized) {
                stateManager.setUnauthenticated()
                setSyncError(resolvedOwnerId, e.message)
                SyncResult.AuthError(e.message)
            } catch (e: AuthException.InvalidCredentials) {
                stateManager.setUnauthenticated()
                setSyncError(resolvedOwnerId, e.message)
                SyncResult.AuthError(e.message)
            } catch (e: AuthException.InvalidRefreshToken) {
                stateManager.setUnauthenticated()
                setSyncError(resolvedOwnerId, e.message)
                SyncResult.AuthError(e.message)
            } catch (e: AuthException.NetworkError) {
                stateManager.setOffline()
                setSyncError(resolvedOwnerId, e.message)
                SyncResult.Offline(e.message)
            } catch (e: IOException) {
                stateManager.setOffline()
                setSyncError(resolvedOwnerId, e.message ?: "Network error")
                SyncResult.Offline(e.message ?: "Network unreachable")
            } catch (e: Exception) {
                stateManager.setError(e.message)
                setSyncError(resolvedOwnerId, e.message)
                SyncResult.Error(e.message ?: "Unknown sync error", e)
            }
        }
    }

    /** Ensures a [SyncMetadataEntity] row exists for [ownerId], inserting a blank one if missing. */
    private suspend fun ensureMetadata(ownerId: String) {
        if (syncMetadataDao.getMetadata(ownerId) == null) {
            syncMetadataDao.upsert(
                SyncMetadataEntity(
                    ownerId = ownerId,
                    deviceId = tokenStorage.getDeviceId(),
                    isSyncing = false
                )
            )
        }
    }

    private suspend fun updateCursor(ownerId: String, cursor: Long, time: String) {
        ensureMetadata(ownerId)
        syncMetadataDao.updateCursor(ownerId, cursor, time)
    }

    private suspend fun setSyncing(ownerId: String, syncing: Boolean) {
        ensureMetadata(ownerId)
        syncMetadataDao.setSyncing(ownerId, syncing)
    }

    private suspend fun updateLastSyncTime(ownerId: String, time: String) {
        ensureMetadata(ownerId)
        syncMetadataDao.updateLastSyncTime(ownerId, time)
    }

    private suspend fun setSyncError(ownerId: String, error: String?) {
        ensureMetadata(ownerId)
        syncMetadataDao.setSyncError(ownerId, error)
    }

    private data class BatchResult(
        val mutationsSynced: Int,
        val changesApplied: Int,
        val conflictsRecorded: Int
    )

    private suspend fun applyBatch(
        ownerId: String,
        pendingMutations: List<SyncOutboxEntity>,
        response: SyncResponse
    ): BatchResult {
        var mutationsSynced = 0
        var changesApplied = 0
        var conflictsRecorded = 0

        val pendingMap = pendingMutations.associateBy { it.id }

        // Step A: Process mutation outcomes
        for (outcome in response.outcomes) {
            val mutation = pendingMap[outcome.mutationId] ?: continue

            when (outcome.status.lowercase()) {
                "accepted" -> {
                    val serverVersion = outcome.version ?: (mutation.baseVersion + 1L)

                    // Check for newer local edits made while this mutation was in flight
                    val newerMutation = syncOutboxDao.getPendingMutationForEntity(
                        ownerId = ownerId,
                        entityType = mutation.entityType,
                        entityId = mutation.entityId
                    )

                    if (newerMutation != null && newerMutation.id != mutation.id) {
                        // Rebase newer pending mutation with updated server baseVersion
                        syncOutboxDao.update(newerMutation.copy(baseVersion = serverVersion))
                    } else {
                        // Update Room entity version directly
                        if (mutation.entityType == "session") {
                            val session = sessionDao.getSessionById(mutation.entityId)
                            if (session != null) {
                                sessionDao.update(session.copy(version = serverVersion))
                            }
                        } else if (mutation.entityType == "solve") {
                            val solve = solveDao.getSolveById(mutation.entityId)
                            if (solve != null) {
                                solveDao.update(solve.copy(version = serverVersion))
                            }
                        }
                    }

                    syncOutboxDao.deleteById(mutation.id)
                    mutationsSynced++
                }

                "rejected" -> {
                    syncOutboxDao.deleteById(mutation.id)
                    mutationsSynced++
                }

                "conflict" -> {
                    syncOutboxDao.deleteById(mutation.id)
                    val serverVersion = outcome.version ?: (mutation.baseVersion + 1L)
                    val serverPayloadJson = outcome.current?.toString()

                    val conflict = conflictResolver.recordConflict(
                        ownerId = ownerId,
                        mutationId = outcome.mutationId,
                        entityType = mutation.entityType,
                        entityId = mutation.entityId,
                        serverVersion = serverVersion,
                        serverUpdatedAt = null,
                        localPayloadJson = mutation.payloadJson,
                        serverPayloadJson = serverPayloadJson,
                        errorMessage = outcome.message ?: "Conflict detected: server version mismatch"
                    )

                    if (defaultConflictPolicy != ConflictPolicy.MANUAL_PROMPT) {
                        conflictResolver.resolveConflict(conflict.conflictId, defaultConflictPolicy)
                    }

                    conflictsRecorded++
                    mutationsSynced++
                }
            }
        }

        // Step B: Process remote changes (Sessions first, Solves second)
        val (sessionChanges, solveChanges) = response.changes.partition { it.entity == "session" }

        // B1. Apply session changes
        for (change in sessionChanges) {
            val pendingCount = syncOutboxDao.countPendingForEntity(ownerId, change.entityId)
            if (pendingCount > 0) {
                // Protect local uncommitted edits
                continue
            }

            val localSession = sessionDao.getSessionById(change.entityId)
            if (localSession != null && localSession.version >= change.version) {
                continue
            }

            if (change.operation == "delete") {
                val deleteTime = change.changedAt ?: CubeTypeConverters.nowIso()
                if (localSession != null) {
                    // Single write: update() already covers deletedAt/updatedAt, so there is no
                    // need for a separate softDelete() call first.
                    sessionDao.update(
                        localSession.copy(
                            version = change.version,
                            deletedAt = deleteTime,
                            updatedAt = deleteTime
                        )
                    )
                } else {
                    sessionDao.softDelete(change.entityId, deletedAt = deleteTime, updatedAt = deleteTime)
                }
                changesApplied++
            } else {
                val dto = change.data?.let {
                    try {
                        json.decodeFromJsonElement<SessionSnapshotDto>(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (dto != null) {
                    val entity = SessionEntity(
                        id = dto.id,
                        ownerId = ownerId,
                        name = dto.name,
                        event = dto.event,
                        kind = dto.kind,
                        startedAt = dto.startedAt,
                        endedAt = dto.endedAt,
                        archived = dto.archived,
                        version = change.version.coerceAtLeast(dto.version),
                        updatedAt = dto.updatedAt ?: change.changedAt ?: dto.startedAt,
                        deletedAt = dto.deletedAt
                    )
                    sessionDao.upsert(entity)
                    changesApplied++
                }
            }
        }

        // B2. Apply solve changes
        for (change in solveChanges) {
            val pendingCount = syncOutboxDao.countPendingForEntity(ownerId, change.entityId)
            if (pendingCount > 0) {
                // Protect local uncommitted edits
                continue
            }

            val localSolve = solveDao.getSolveById(change.entityId)
            if (localSolve != null && localSolve.version >= change.version) {
                continue
            }

            if (change.operation == "delete") {
                val deleteTime = change.changedAt ?: CubeTypeConverters.nowIso()
                if (localSolve != null) {
                    // Single write: update() already covers deletedAt/updatedAt, so there is no
                    // need for a separate softDelete() call first.
                    solveDao.update(
                        localSolve.copy(
                            version = change.version,
                            deletedAt = deleteTime,
                            updatedAt = deleteTime
                        )
                    )
                } else {
                    solveDao.softDelete(change.entityId, deletedAt = deleteTime, updatedAt = deleteTime)
                }
                changesApplied++
            } else {
                val dto = change.data?.let {
                    try {
                        json.decodeFromJsonElement<SolveSnapshotDto>(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (dto != null) {
                    val entity = SolveEntity(
                        id = dto.id,
                        ownerId = ownerId,
                        sessionId = dto.sessionId,
                        durationMs = dto.durationMs,
                        penalty = dto.penalty,
                        solvedAt = dto.solvedAt,
                        scramble = dto.scramble,
                        event = dto.event,
                        version = change.version.coerceAtLeast(dto.version),
                        updatedAt = dto.updatedAt ?: change.changedAt ?: dto.solvedAt,
                        deletedAt = dto.deletedAt
                    )
                    solveDao.upsert(entity)
                    changesApplied++
                }
            }
        }

        // Reset any unanswered mutations in this batch back to pending
        val answeredIds = response.outcomes.map { it.mutationId }.toSet()
        for (m in pendingMutations) {
            if (m.id !in answeredIds) {
                val currentInDb = syncOutboxDao.getMutationById(m.id)
                if (currentInDb != null) {
                    syncOutboxDao.update(currentInDb.copy(status = "pending"))
                }
            }
        }

        // Step C: Advance watermark cursor strictly within transaction
        if (response.nextCursor > 0L) {
            val nowIso = CubeTypeConverters.nowIso()
            updateCursor(ownerId, response.nextCursor, nowIso)
        }

        return BatchResult(mutationsSynced, changesApplied, conflictsRecorded)
    }

    override suspend fun runSnapshotBootstrap(ownerId: String): Long = withContext(ioDispatcher) {
        var watermarkCursor = 0L
        var currentEntity = "session"
        var afterId = "00000000-0000-0000-0000-000000000000"
        var hasMore = true
        var pageCount = 0
        val maxPages = 100

        val device = DeviceDto(
            id = tokenStorage.getDeviceId(),
            name = "Android Device",
            platform = "android"
        )

        while (hasMore && pageCount < maxPages) {
            pageCount++
            val request = SnapshotRequest(
                device = device,
                cursor = watermarkCursor,
                afterId = afterId,
                entity = currentEntity,
                pageSize = 500
            )

            val response = apiClient.snapshot(request)
            if (response.cursor > 0L) {
                watermarkCursor = response.cursor
            }

            database.withTransaction {
                response.sessions?.let { sessions ->
                    // Same protection as the incremental sync path (see applyBatch): don't let a
                    // snapshot row clobber a local edit that hasn't reached the server yet.
                    val entities = sessions.mapNotNull { dto ->
                        if (syncOutboxDao.countPendingForEntity(ownerId, dto.id) > 0) {
                            return@mapNotNull null
                        }
                        SessionEntity(
                            id = dto.id,
                            ownerId = ownerId,
                            name = dto.name,
                            event = dto.event,
                            kind = dto.kind,
                            startedAt = dto.startedAt,
                            endedAt = dto.endedAt,
                            archived = dto.archived,
                            version = dto.version,
                            updatedAt = dto.updatedAt ?: dto.startedAt,
                            deletedAt = dto.deletedAt
                        )
                    }
                    if (entities.isNotEmpty()) {
                        sessionDao.upsertAll(entities)
                    }
                }

                response.solves?.let { solves ->
                    val entities = solves.mapNotNull { dto ->
                        if (syncOutboxDao.countPendingForEntity(ownerId, dto.id) > 0) {
                            return@mapNotNull null
                        }
                        SolveEntity(
                            id = dto.id,
                            ownerId = ownerId,
                            sessionId = dto.sessionId,
                            durationMs = dto.durationMs,
                            penalty = dto.penalty,
                            solvedAt = dto.solvedAt,
                            scramble = dto.scramble,
                            event = dto.event,
                            version = dto.version,
                            updatedAt = dto.updatedAt ?: dto.solvedAt,
                            deletedAt = dto.deletedAt
                        )
                    }
                    if (entities.isNotEmpty()) {
                        solveDao.upsertAll(entities)
                    }
                }
            }

            if (response.hasMore) {
                currentEntity = response.nextEntity ?: if (!response.sessions.isNullOrEmpty()) "session" else "solve"
                afterId = response.nextAfterId ?: "00000000-0000-0000-0000-000000000000"
            } else {
                hasMore = false
            }
        }

        val nowIso = CubeTypeConverters.nowIso()
        updateCursor(ownerId, watermarkCursor, nowIso)
        watermarkCursor
    }
}
