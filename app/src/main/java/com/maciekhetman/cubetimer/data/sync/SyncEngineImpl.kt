package com.maciekhetman.cubetimer.data.sync

import android.util.Log
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
import com.maciekhetman.cubetimer.data.local.dao.markAllFailedChunked
import com.maciekhetman.cubetimer.data.local.dao.markInFlightChunked
import com.maciekhetman.cubetimer.data.local.dao.resetInFlightChunked
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.DeviceDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncMutationDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException

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
    private val defaultConflictPolicy: ConflictPolicy = ConflictPolicy.MANUAL_PROMPT,
    /** Upper bound on waiting for [AuthManager.awaitInitialized] before a sync gives up (retryably). */
    private val authInitTimeoutMillis: Long = DEFAULT_AUTH_INIT_TIMEOUT_MILLIS,
    /**
     * Safety ceiling on the snapshot pages one bootstrap may fetch. It is not a size limit for the
     * account (the bootstrap pages until the server says it is done); it only stops a server that
     * keeps handing out fresh page positions forever. Hitting it fails the bootstrap.
     */
    private val maxSnapshotPages: Int = DEFAULT_MAX_SNAPSHOT_PAGES,
    /**
     * Safety ceiling on the sync/outbox passes one [sync] call may run. A server that keeps
     * answering `has_more` would otherwise loop forever. Hitting it does not commit a "fully
     * synced" status: the cursor already advanced, and the result asks the worker to continue.
     */
    private val maxSyncPasses: Int = DEFAULT_MAX_SYNC_PASSES
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

    override suspend fun sync(ownerId: String?): SyncResult {
        // When WorkManager cold-starts the process to sync, the auth manager is only just restoring
        // the session: authState is still Loading and the sync would quietly no-op. Leaving Loading
        // isn't enough either - the cached identity is published before the startup refresh, and
        // until that returns there is no access token, so our request would 401 and
        // TokenAuthenticator would refresh with the same refresh token concurrently (tripping the
        // server's reuse detection). So wait for initialization to finish - also for an explicit
        // ownerId, which needs the same token; only an explicit guest never reaches the server.
        // Bounded, and before syncMutex so a stuck wait can't hold up other syncs.
        val explicitGuest = ownerId != null && (ownerId == "guest" || ownerId.isBlank())
        if (!explicitGuest) {
            val initialized = withTimeoutOrNull(authInitTimeoutMillis) { authManager.awaitInitialized() }
            if (initialized == null) {
                // Not NoOp: the worker must retry rather than report success with nothing uploaded.
                stateManager.setOffline()
                return SyncResult.Offline("Timed out waiting for the signed-in session to be restored")
            }
        }
        return syncAfterAuthInitialized(ownerId)
    }

    private suspend fun syncAfterAuthInitialized(ownerId: String?): SyncResult = withContext(ioDispatcher) {
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

            val run = SyncRun()
            var hasMore = true
            var loopCount = 0

            try {
                while (hasMore && loopCount < maxSyncPasses) {
                    loopCount++
                    hasMore = sendOutboxBatch(resolvedOwnerId, run)
                }

                if (hasMore) {
                    // Pages already applied keep their cursor. Don't stamp last_sync_time or a
                    // sticky error: this is a pause, and the follow-up sync should be free to
                    // report success. The in-memory error is only so the UI doesn't say "synced"
                    // while the worker schedules the continuation.
                    setSyncing(resolvedOwnerId, false)
                    val message = "Sync paused after $maxSyncPasses batches and will continue"
                    stateManager.setError(message)
                    return@withContext SyncResult.Error(message)
                }

                val nowEpoch = System.currentTimeMillis()
                val nowIso = CubeTypeConverters.nowIso()
                setSyncing(resolvedOwnerId, false)
                updateLastSyncTime(resolvedOwnerId, nowIso)
                stateManager.setSynced(nowEpoch)
                if (run.deadLettered > 0) {
                    // Everything else got through, so this is still a Success (a retry can't help),
                    // but the user must hear that some changes will never upload. Raised after
                    // updateLastSyncTime because that clears the sticky error; the next sync that
                    // completes clears this one in turn.
                    setSyncError(resolvedOwnerId, deadLetterMessage(run))
                }

                SyncResult.Success(
                    mutationsSynced = run.mutationsSynced,
                    changesApplied = run.changesApplied,
                    conflictsRecorded = run.conflictsRecorded
                )
            } catch (e: CancellationException) {
                // The worker was stopped (typically WorkManager cancelling it when the network
                // drops mid-sync). Don't report that as a sync error, but clear the persisted
                // is_syncing flag - the coroutine is already cancelled, so this write needs
                // NonCancellable - otherwise the UI keeps showing SYNCING until the next sync.
                withContext(NonCancellable) { setSyncing(resolvedOwnerId, false) }
                stateManager.setOffline()
                throw e
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
                // Not a sync error: nothing is wrong that a later attempt can't fix, and a persisted
                // last_error would show ERROR (and the badge) until some sync succeeds. Only the
                // syncing flag is cleared, which setSyncError would otherwise have done.
                stateManager.setOffline()
                setSyncing(resolvedOwnerId, false)
                SyncResult.Offline(e.message)
            } catch (e: IOException) {
                stateManager.setOffline()
                setSyncing(resolvedOwnerId, false)
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

    /** Running totals of one [sync] call, across all its outbox passes. */
    private class SyncRun {
        var mutationsSynced = 0
        var changesApplied = 0
        var conflictsRecorded = 0

        /** Mutations dead-lettered by this run (permanently rejected on their own). */
        var deadLettered = 0
        var lastDeadLetterError: String? = null

        /** Requests the server rejected outright (see [isPermanentRejection]) in this run. */
        var rejectedRequests = 0

        /** Whether the server has accepted at least one request in this run, i.e. the request envelope is sound. */
        var requestSucceeded = false
    }

    private enum class Recovery {
        /** The failed batch was dealt with (split up or queued for a probe); carry on with the queue. */
        CONTINUE,

        /** The failed batch was a lone mutation the server rejected; it is now dead. Carry on with the queue. */
        DEAD_LETTERED,

        /** The cursor was reset and a snapshot bootstrap ran; re-read the outbox and start the pass over. */
        RESTART_PASS
    }

    /**
     * One pass over the outbox: sends up to [OUTBOX_BATCH_SIZE] queued mutations (at most one per
     * entity) and applies the responses. Returns whether another pass is needed.
     */
    private suspend fun sendOutboxBatch(ownerId: String, run: SyncRun): Boolean {
        // 1. Fetch pending outbox mutations
        val pending = syncOutboxDao.getPendingMutations(ownerId, limit = OUTBOX_BATCH_SIZE)
        val attemptAt = System.currentTimeMillis()
        if (pending.isNotEmpty()) {
            syncOutboxDao.markInFlightChunked(pending.map { it.id }, attemptAt)
        }

        // 2. One mutation per entity (see coalescePerEntity). Normally this goes out as a single
        // request. If the server rejects the whole request, the batch is split in halves, both
        // put back on the front of the queue, to isolate the mutation responsible without
        // holding up the rest (see handleRequestFailure).
        val queue = ArrayDeque<OutgoingBatch>()
        queue.addLast(coalescePerEntity(pending))
        var responseHasMore = false
        var reviveSuperseded = false

        while (queue.isNotEmpty()) {
            val batch = queue.removeFirst()

            // 3. Send. Each request reads the cursor afresh: the previous part's response has
            // already advanced it.
            val response: SyncResponse = try {
                apiClient.sync(buildSyncRequest(ownerId, batch))
            } catch (e: CancellationException) {
                // Not a failed attempt: leave the rows in_flight, the next sync resets them.
                throw e
            } catch (e: Exception) {
                // Recovers (returning) or gives up (throwing) - never falls through.
                when (handleRequestFailure(ownerId, e, batch, queue, run, attemptAt)) {
                    Recovery.RESTART_PASS -> return true
                    // The older edits the dead mutation had superseded went back to pending.
                    Recovery.DEAD_LETTERED -> if (batch.supersededIds.isNotEmpty()) reviveSuperseded = true
                    Recovery.CONTINUE -> Unit
                }
                continue
            }
            run.requestSucceeded = true

            // 4. Apply outcomes and changes inside database transaction
            val batchResult = database.withTransaction {
                applyBatch(ownerId, batch, response)
            }
            run.mutationsSynced += batchResult.mutationsSynced
            run.changesApplied += batchResult.changesApplied
            run.conflictsRecorded += batchResult.conflictsRecorded
            responseHasMore = response.hasMore
        }

        // 5. Check if pagination loop should continue
        val remainingPending = syncOutboxDao.countPending(ownerId)
        return responseHasMore || reviveSuperseded ||
            (pending.size == OUTBOX_BATCH_SIZE && remainingPending > 0)
    }

    private fun deviceFor(metadata: SyncMetadataEntity?): DeviceDto = DeviceDto(
        id = tokenStorage.getDeviceId(),
        name = metadata?.deviceName ?: "Android Device",
        platform = metadata?.devicePlatform ?: "android"
    )

    /** Live outbox entities, loaded once per page instead of once per incoming change. */
    private suspend fun liveEntityKeys(ownerId: String): Set<Pair<String, String>> =
        syncOutboxDao.getLiveEntityKeys(ownerId).mapTo(HashSet()) { it.entityType to it.entityId }

    private suspend fun buildSyncRequest(ownerId: String, batch: OutgoingBatch): SyncRequest {
        val metadata = syncMetadataDao.getMetadata(ownerId)
        val device = deviceFor(metadata)
        return SyncRequest(
            cursor = metadata?.cursor ?: 0L,
            device = device,
            mutations = batch.mutations.map { mutation ->
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
            },
            limit = SYNC_PAGE_LIMIT
        )
    }

    /**
     * Decides what a failed `POST /v1/sync` means for the rows that were in flight ([batch] plus
     * everything still [queue]d behind it).
     *
     * - Cursor expired: hand the rows back, reset the cursor and bootstrap from a snapshot.
     * - The server permanently rejected the request itself ([isPermanentRejection]): almost always
     *   one malformed mutation (the server decodes strictly), which without this would fail the
     *   same first batch on every sync and starve everything queued behind it. Split a multi-
     *   mutation batch in halves to find the offender while the good halves still upload; a lone
     *   mutation that is rejected on its own is dead-lettered ([deadLetter]).
     * - Anything else (network, 5xx, 429, auth, ...) is transient: mark the rows `failed`, which
     *   are retried on the next sync, and rethrow.
     */
    private suspend fun handleRequestFailure(
        ownerId: String,
        e: Exception,
        batch: OutgoingBatch,
        queue: ArrayDeque<OutgoingBatch>,
        run: SyncRun,
        attemptAt: Long
    ): Recovery {
        if (isCursorExpired(e)) {
            syncOutboxDao.resetInFlightChunked(inFlightIds(batch, queue))
            queue.clear()
            updateCursor(ownerId, 0L, CubeTypeConverters.nowIso())
            runSnapshotBootstrap(ownerId)
            return Recovery.RESTART_PASS
        }

        if (e is AuthException.ApiError && isPermanentRejection(e) && batch.mutations.isNotEmpty() &&
            ++run.rejectedRequests <= MAX_REJECTED_REQUESTS_PER_SYNC
        ) {
            if (batch.mutations.size > 1) {
                val (first, second) = batch.split()
                queue.addFirst(second)
                queue.addFirst(first)
                return Recovery.CONTINUE
            }

            if (!run.requestSucceeded) {
                // A lone mutation is rejected, but nothing has gone through yet in this sync, so the
                // rejection may not be about the mutation at all (a request the server can't
                // parse, a cursor or device it dislikes, an unsupported protocol version...).
                // Dead-lettering on that evidence could write off the whole outbox one row at a
                // time. First send the same request without mutations: if the server rejects that
                // too, the fault is not the mutation (empty batches aren't dead-lettered, so it
                // fails through the transient path below and the row stays retryable); if it
                // accepts, this attempt is repeated and, with the envelope now vouched for,
                // dead-lettered.
                queue.addFirst(batch)
                queue.addFirst(OutgoingBatch(emptyList(), emptyMap()))
                return Recovery.CONTINUE
            }

            deadLetter(batch, e, run, attemptAt)
            return Recovery.DEAD_LETTERED
        }

        // Transient (or a rejection that isn't attributable to a mutation).
        val remaining = inFlightIds(batch, queue)
        if (remaining.isNotEmpty()) {
            syncOutboxDao.markAllFailedChunked(remaining, e.message, attemptAt)
        }
        throw e
    }

    /**
     * Gives up on the single mutation in [batch] (the server rejected it on its own): its row
     * becomes `dead` - kept with `last_error`, never sent again, no longer counted as pending nor
     * protecting its entity from incoming remote changes. The older edits it had superseded go back
     * to pending; they were valid states of the entity and get their own chance on the next pass.
     */
    private suspend fun deadLetter(
        batch: OutgoingBatch,
        e: AuthException.ApiError,
        run: SyncRun,
        attemptAt: Long
    ) {
        val mutation = batch.mutations.single()
        val error = "Rejected by the server (HTTP ${e.httpStatusCode} ${e.errorCode}): ${e.message}"
        database.withTransaction {
            syncOutboxDao.markDead(mutation.id, error, attemptAt)
            batch.supersededIds[mutation.id]?.let { syncOutboxDao.resetInFlightChunked(it) }
        }
        run.deadLettered++
        run.lastDeadLetterError = error
    }

    private fun deadLetterMessage(run: SyncRun): String {
        val changes = if (run.deadLettered == 1) "1 change was" else "${run.deadLettered} changes were"
        return "$changes rejected by the server and won't be retried. ${run.lastDeadLetterError}"
    }

    /** Ids of every outbox row still in flight if [batch] and everything [queue]d behind it is abandoned. */
    private fun inFlightIds(batch: OutgoingBatch, queue: Collection<OutgoingBatch>): List<String> =
        batch.allIds() + queue.flatMap { it.allIds() }

    /**
     * Whether [e] means the sync cursor is too old (HTTP 409 `cursor_expired`) and only a snapshot
     * bootstrap can catch the client up. A 409 that names some other error code is not that and
     * must not trigger a full re-download; a 409 that names no usable code is taken as cursor
     * expiry, since that is the only 409 `/v1/sync` documents.
     */
    private fun isCursorExpired(e: Exception): Boolean = when (e) {
        is AuthException.CursorExpired -> true
        is AuthException.ApiError ->
            e.errorCode.equals("cursor_expired", ignoreCase = true) ||
                (e.httpStatusCode == 409 && (e.errorCode.isBlank() || e.errorCode == UNKNOWN_ERROR_CODE))
        else -> false
    }

    /**
     * Whether the server refused the request itself in a way retrying the same request can never
     * fix: a 4xx other than the ones that say "not now / not you" - 401/403 (auth, handled by the
     * token refresh and re-login flow), 408/425/429 (timeouts, back-off), and 409 (cursor state).
     */
    private fun isPermanentRejection(e: AuthException.ApiError): Boolean =
        e.httpStatusCode in 400..499 && e.httpStatusCode !in RETRYABLE_4XX_STATUSES

    /**
     * Whether a remote solve may be written, given the session it names. `solves.session_id` is a
     * deferred foreign key, so a solve whose session row is missing (e.g. the session's payload
     * could not be decoded) does not fail its own write but the commit of the whole page, which
     * then fails the same way on every retry and never advances the cursor. Such a solve is skipped
     * instead; clearing its `session_id` is not an option, as the next upload would change the
     * server's copy. Lookups are remembered for one page: no session row is added or removed while
     * that page's solves are applied.
     */
    private inner class SessionReferences {
        private val present = HashMap<String, Boolean>()

        suspend fun canReference(sessionId: String?): Boolean =
            sessionId == null || present.getOrPut(sessionId) { sessionDao.getSessionById(sessionId) != null }
    }

    private data class BatchResult(
        val mutationsSynced: Int,
        val changesApplied: Int,
        val conflictsRecorded: Int
    )

    /**
     * What one sync request sends: at most one mutation per entity, plus the older queued mutations
     * for that entity it supersedes (keyed by the sent mutation's id).
     */
    private class OutgoingBatch(
        val mutations: List<SyncOutboxEntity>,
        val supersededIds: Map<String, List<String>>
    ) {
        /** Every outbox row this batch stands for: the mutations sent and the ones they supersede. */
        fun allIds(): List<String> = mutations.flatMap { listOf(it.id) + supersededIds[it.id].orEmpty() }

        /** Splits into the first half and the rest, each mutation keeping its superseded rows. Needs >= 2 mutations. */
        fun split(): Pair<OutgoingBatch, OutgoingBatch> {
            val mid = mutations.size / 2
            return part(mutations.subList(0, mid)) to part(mutations.subList(mid, mutations.size))
        }

        private fun part(subset: List<SyncOutboxEntity>) = OutgoingBatch(
            subset,
            subset.mapNotNull { m -> supersededIds[m.id]?.let { m.id to it } }.toMap()
        )
    }

    /**
     * The server applies a request's mutations one at a time, each checked against the entity's
     * current version. Two queued edits of the same entity (a solve saved and then given a +2, or
     * saved and then deleted, while offline) both carry the base version the client last saw, so
     * the server accepts the first and answers the second with a conflict: the stale value stays
     * on the server and the user gets a conflict against their own edit.
     *
     * So send only the latest mutation per entity. It already carries the entity's full current
     * state (upserts send the whole row) or the delete; its base version is the highest in the
     * group, i.e. the newest server version the client has learned (an accepted outcome rebases
     * only the newest pending mutation). The older mutations are settled by its outcome.
     */
    private fun coalescePerEntity(pending: List<SyncOutboxEntity>): OutgoingBatch {
        val mutations = ArrayList<SyncOutboxEntity>()
        val supersededIds = HashMap<String, List<String>>()
        // Pending is ordered oldest first, so each group's last element is its latest mutation.
        for (group in pending.groupBy { it.entityType to it.entityId }.values) {
            val latest = group.last()
            mutations += latest.copy(baseVersion = group.maxOf { it.baseVersion })
            if (group.size > 1) supersededIds[latest.id] = group.dropLast(1).map { it.id }
        }
        return OutgoingBatch(mutations, supersededIds)
    }

    private suspend fun applyBatch(
        ownerId: String,
        outgoing: OutgoingBatch,
        response: SyncResponse
    ): BatchResult {
        var mutationsSynced = 0
        var changesApplied = 0
        var conflictsRecorded = 0

        val pendingMap = outgoing.mutations.associateBy { it.id }

        // Step A: Process mutation outcomes
        for (outcome in response.outcomes) {
            val mutation = pendingMap[outcome.mutationId] ?: continue
            // Whatever the outcome, it settles the older mutations this one superseded.
            outgoing.supersededIds[mutation.id]?.let { syncOutboxDao.deleteMutations(it) }

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
        val protectedEntities = liveEntityKeys(ownerId)

        // B1. Apply session changes
        for (change in sessionChanges) {
            if ("session" to change.entityId in protectedEntities) {
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
                } else {
                    Log.w(TAG, "Skipping remote session ${change.entityId}: its payload could not be decoded")
                }
            }
        }

        // B2. Apply solve changes
        val sessionReferences = SessionReferences()
        for (change in solveChanges) {
            if ("solve" to change.entityId in protectedEntities) {
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
                    if (!sessionReferences.canReference(dto.sessionId)) {
                        Log.w(TAG, "Skipping remote solve ${dto.id}: its session ${dto.sessionId} is not available locally")
                        continue
                    }
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
                        deletedAt = dto.deletedAt,
                        timingDevice = dto.timingDevice
                    )
                    solveDao.upsert(entity)
                    changesApplied++
                }
            }
        }

        // Reset any unanswered mutations in this batch back to pending
        val answeredIds = response.outcomes.map { it.mutationId }.toSet()
        for (m in outgoing.mutations) {
            if (m.id !in answeredIds) {
                val currentInDb = syncOutboxDao.getMutationById(m.id)
                if (currentInDb != null) {
                    syncOutboxDao.update(currentInDb.copy(status = "pending"))
                }
                outgoing.supersededIds[m.id]?.let { syncOutboxDao.resetInFlight(it) }
            }
        }

        // Step C: Advance watermark cursor strictly within transaction
        if (response.nextCursor > 0L) {
            val nowIso = CubeTypeConverters.nowIso()
            updateCursor(ownerId, response.nextCursor, nowIso)
        }

        return BatchResult(mutationsSynced, changesApplied, conflictsRecorded)
    }

    /**
     * Pages `POST /v1/snapshot` (all sessions, then all solves) until the server says it is done -
     * however many pages that takes - and only then commits the watermark cursor.
     *
     * The cursor is the point the client resumes incremental sync from, so committing it means
     * "everything before this has arrived". A bootstrap that stops early must therefore never
     * commit it, or whatever wasn't fetched is skipped for good. Hence the guards: a server that
     * doesn't advance (a page position asked for twice, `has_more` with nowhere to go on from) or
     * that never stops offering new positions ([maxSnapshotPages]) fails the bootstrap with a
     * [SnapshotBootstrapException] instead. Pages already fetched stay applied (upserts of
     * server rows, harmless to repeat); the next sync starts the bootstrap over.
     */
    override suspend fun runSnapshotBootstrap(ownerId: String): Long = withContext(ioDispatcher) {
        var watermarkCursor = 0L
        var currentEntity = "session"
        var afterId = ZERO_UUID
        var hasMore = true
        var pageCount = 0
        // Every (entity, after_id) position requested so far. Paging is keyset-based, so being
        // sent to a position again means the server is going round in circles.
        val requested = HashSet<Pair<String, String>>()
        requested += currentEntity to afterId

        val device = deviceFor(syncMetadataDao.getMetadata(ownerId))

        while (hasMore) {
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
                val protectedEntities = liveEntityKeys(ownerId)
                response.sessions?.let { sessions ->
                    // Same protection as the incremental sync path (see applyBatch): don't let a
                    // snapshot row clobber a local edit that hasn't reached the server yet.
                    val entities = sessions.mapNotNull { dto ->
                        if ("session" to dto.id in protectedEntities) {
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
                    val sessionReferences = SessionReferences()
                    val entities = solves.mapNotNull { dto ->
                        if ("solve" to dto.id in protectedEntities) {
                            return@mapNotNull null
                        }
                        if (!sessionReferences.canReference(dto.sessionId)) {
                            Log.w(TAG, "Skipping snapshot solve ${dto.id}: its session ${dto.sessionId} is not available locally")
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
                            deletedAt = dto.deletedAt,
                            timingDevice = dto.timingDevice
                        )
                    }
                    if (entities.isNotEmpty()) {
                        solveDao.upsertAll(entities)
                    }
                }
            }

            // The server streams every session page, then every solve page. When the session pages
            // run out it answers has_more = false with next_entity = "solve": that is a hand-over to
            // the solve pages (starting again from the zero UUID), not the end of the bootstrap.
            val nextEntity = response.nextEntity
            val nextPosition: Pair<String, String>? = if (response.hasMore) {
                val entity = nextEntity ?: currentEntity
                if (entity == currentEntity) {
                    // Continuing this entity needs the id to resume after; without one the next
                    // request would restart from the zero UUID and fetch the same page again.
                    val resumeAfter = response.nextAfterId ?: throw SnapshotBootstrapException(
                        "Snapshot bootstrap stalled: server reported has_more for '$currentEntity' " +
                            "after page $pageCount without a next_after_id"
                    )
                    entity to resumeAfter
                } else {
                    entity to ZERO_UUID
                }
            } else if (nextEntity != null && nextEntity != currentEntity) {
                nextEntity to ZERO_UUID
            } else {
                null
            }

            if (nextPosition == null) {
                hasMore = false
            } else {
                if (!requested.add(nextPosition)) {
                    throw SnapshotBootstrapException(
                        "Snapshot bootstrap is not advancing: server sent the client back to " +
                            "'${nextPosition.first}' after ${nextPosition.second} on page $pageCount"
                    )
                }
                if (pageCount >= maxSnapshotPages) {
                    throw SnapshotBootstrapException(
                        "Snapshot bootstrap exceeded the safety limit of $maxSnapshotPages pages"
                    )
                }
                currentEntity = nextPosition.first
                afterId = nextPosition.second
            }
        }

        val nowIso = CubeTypeConverters.nowIso()
        updateCursor(ownerId, watermarkCursor, nowIso)
        watermarkCursor
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

        /** Outbox rows fetched (and mutations sent, after coalescing) per request. */
        const val OUTBOX_BATCH_SIZE = 500

        /** The `limit` sent with every sync request: how many remote changes the server may return. */
        const val SYNC_PAGE_LIMIT = 500

        /**
         * Requests the server may reject outright in one [sync] before it stops hunting for the
         * offending mutation and fails the sync (rows stay retryable). Isolating one bad mutation
         * in a full batch takes about 2 * log2(500) = 18 of these, so this leaves room for several
         * offenders while bounding the traffic (and the number of rows that can be dead-lettered)
         * when the server rejects everything it is sent.
         */
        const val MAX_REJECTED_REQUESTS_PER_SYNC = 128

        /** [AuthException.ApiError.errorCode] that [ErrorParser] substitutes when the server sent none. */
        const val UNKNOWN_ERROR_CODE = "unknown_error"

        /** 4xx statuses that are about timing, auth or cursor state rather than the request's content. */
        val RETRYABLE_4XX_STATUSES = setOf(401, 403, 408, 409, 425, 429)

        /**
         * Default for [maxSnapshotPages]: 25 million rows at 500 per page, far beyond any account.
         */
        const val DEFAULT_MAX_SNAPSHOT_PAGES = 50_000

        /** Default for [maxSyncPasses]. 50 pages is 25k remote changes plus a full outbox drain. */
        const val DEFAULT_MAX_SYNC_PASSES = 50

        /**
         * The startup refresh is one request under OkHttp's 15 s connect and 15 s read timeouts: a
         * refresh that times out ends in ~15 s, and even one slow in both phases fits. Anything
         * longer ends the sync with a retryable [SyncResult.Offline].
         */
        const val DEFAULT_AUTH_INIT_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * A snapshot bootstrap that could not be brought to a trustworthy end (the server stopped
 * advancing, or never stopped). The watermark cursor is not committed when this is thrown, so the
 * next sync retries the bootstrap instead of treating a partial download as complete.
 */
class SnapshotBootstrapException(message: String) : IllegalStateException(message)
