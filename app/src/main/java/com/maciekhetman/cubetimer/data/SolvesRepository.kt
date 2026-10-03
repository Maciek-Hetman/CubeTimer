package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.dao.getSolvesByIdsChunked
import com.maciekhetman.cubetimer.data.local.dao.softDeleteAllChunked
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toDbString
import com.maciekhetman.cubetimer.data.local.mapper.toDeleteMutation
import com.maciekhetman.cubetimer.data.local.mapper.toEventString
import com.maciekhetman.cubetimer.data.local.mapper.toSolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SolvesRepository(
    private val context: Context,
    private val solveDao: SolveDao,
    private val sessionDao: SessionDao,
    private val syncOutboxDao: SyncOutboxDao,
    private val database: CubeDatabase? = null,
    private val json: Json = NetworkModule.json,
    private val syncTrigger: (suspend () -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // Secondary constructor for 4 parameters (used by existing tests)
    constructor(
        context: Context,
        solveDao: SolveDao,
        sessionDao: SessionDao,
        database: CubeDatabase? = null
    ) : this(
        context = context,
        solveDao = solveDao,
        sessionDao = sessionDao,
        syncOutboxDao = database?.syncOutboxDao() ?: CubeDatabase.getInstance(context).syncOutboxDao(),
        database = database,
        ioDispatcher = Dispatchers.IO
    )

    // Secondary constructor for seamless backwards compatibility with ViewModel instantiations: SolvesRepository(application)
    constructor(context: Context) : this(
        context = context,
        solveDao = CubeDatabase.getInstance(context).solveDao(),
        sessionDao = CubeDatabase.getInstance(context).sessionDao(),
        syncOutboxDao = CubeDatabase.getInstance(context).syncOutboxDao(),
        database = CubeDatabase.getInstance(context),
        ioDispatcher = Dispatchers.IO
    )

    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        // Trigger migration asynchronously on repository initialization if database reference is present
        database?.let { db ->
            repositoryScope.launch {
                DataStoreMigration(context, db).migrateIfNeeded()
            }
        }
    }

    /**
     * Runs [block] inside a Room transaction when a [database] reference is available, so the
     * Room row write and its outbox mutation are committed atomically; falls back to running
     * [block] directly (no transaction) for call sites/tests that only provide bare DAOs.
     */
    private suspend fun <T> runInTransaction(block: suspend () -> T): T {
        return if (database != null) database.withTransaction { block() } else block()
    }

    /**
     * [existing] overwritten with [solve]'s content and revived (`deleted_at` cleared) at [updatedAt].
     * Its id, server version and timing device are kept, and so is the stored event string when it
     * names an event this app has no [Mode] for (see [CubeTypeConverters.eventForRewrite]).
     */
    private fun rewriteExisting(
        existing: SolveEntity,
        solve: SolveTime,
        ownerId: String,
        sessionId: String?,
        updatedAt: String
    ): SolveEntity = existing.copy(
        ownerId = ownerId,
        sessionId = sessionId,
        event = CubeTypeConverters.eventForRewrite(existing.event, solve.mode),
        durationMs = solve.timeInMillis,
        penalty = CubeTypeConverters.fromPenalty(solve.penalty),
        solvedAt = CubeTypeConverters.epochMillisToIso(solve.timestamp),
        scramble = solve.scramble,
        deletedAt = null,
        updatedAt = updatedAt
    )

    /**
     * Observe all active solves for a specific owner.
     */
    fun getAllSolvesFlow(ownerId: String = "guest"): Flow<List<SolveTime>> {
        return solveDao.observeAllSolves(ownerId = ownerId)
            .map { entities -> entities.map { it.toSolveTime() } }
    }

    // --- Historical PB Lookup ---

    suspend fun getPriorBestSolveDuration(
        mode: Mode,
        solvedAtIso: String,
        ownerId: String = "guest",
        excludeSolveId: String? = null
    ): Long? = withContext(ioDispatcher) {
        solveDao.getPriorBestSolveDuration(
            ownerId = ownerId,
            event = mode.toEventString(),
            solvedAt = solvedAtIso,
            excludeSolveId = excludeSolveId
        )
    }

    /**
     * Save a single solve with session association and transactional outbox mutation dispatch.
     * If a row with this id already exists (e.g. re-adding a solve after an undo), its server
     * [SolveEntity.version] is preserved instead of being reset to 0, so sync doesn't send a
     * stale baseVersion and provoke a needless server conflict.
     */
    suspend fun saveSolve(
        solve: SolveTime,
        ownerId: String = "guest",
        sessionId: String? = solve.sessionId
    ) = withContext(ioDispatcher) {
        val nowIso = CubeTypeConverters.nowIso()

        runInTransaction {
            val existing = solveDao.getSolveById(solve.id)
            val entity = if (existing != null) {
                rewriteExisting(existing, solve, ownerId, sessionId = sessionId ?: solve.sessionId, updatedAt = nowIso)
            } else {
                solve.toSolveEntity(ownerId = ownerId, sessionId = sessionId ?: solve.sessionId)
            }

            solveDao.upsert(entity)
            if (ownerId != "guest") {
                syncOutboxDao.enqueue(entity.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json))
            }
        }
        syncTrigger?.invoke()
    }

    /**
     * Delete a single solve (soft delete with timestamp) and enqueue delete mutation if authenticated.
     */
    suspend fun deleteSolve(
        solve: SolveTime,
        ownerId: String = "guest"
    ) = deleteSolves(listOf(solve), ownerId)

    /**
     * Batch delete multiple solves (soft delete with timestamp) and enqueue delete mutations if authenticated.
     * Only rows actually owned by [ownerId] are touched, matching [deleteSolvesByIds].
     */
    suspend fun deleteSolves(
        solves: List<SolveTime>,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        if (solves.isEmpty()) return@withContext
        deleteSolvesByIds(solves.map { it.id }, ownerId)
        Unit
    }

    /**
     * Batch soft-delete solves matching the provided IDs.
     * Enqueues delete outbox mutations if ownerId != "guest", triggers sync,
     * and returns the list of deleted [SolveTime]s for undo snapshotting.
     */
    suspend fun deleteSolvesByIds(
        ids: List<String>,
        ownerId: String = "guest"
    ): List<SolveTime> = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext emptyList()

        val nowIso = CubeTypeConverters.nowIso()
        val deletedSolves = mutableListOf<SolveTime>()

        runInTransaction {
            val existing = solveDao.getSolvesByIdsChunked(ids).filter { it.deletedAt == null && it.ownerId == ownerId }
            if (existing.isEmpty()) return@runInTransaction

            val targetIds = existing.map { it.id }
            deletedSolves.addAll(existing.map { it.toSolveTime() })

            solveDao.softDeleteAllChunked(targetIds, deletedAt = nowIso, updatedAt = nowIso)
            if (ownerId != "guest") {
                syncOutboxDao.enqueueAll(existing.map { it.toDeleteMutation(ownerId = ownerId, clientTime = nowIso) })
            }
        }

        if (deletedSolves.isNotEmpty()) syncTrigger?.invoke()
        deletedSolves
    }

    /**
     * Update penalty for an existing solve and enqueue upsert mutation if authenticated.
     */
    suspend fun updateSolvePenalty(
        solve: SolveTime,
        penalty: Penalty,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        val nowIso = CubeTypeConverters.nowIso()

        runInTransaction {
            val existing = solveDao.getSolveById(solve.id)
            val updated = if (existing != null) {
                existing.copy(
                    penalty = penalty.toDbString(),
                    updatedAt = nowIso
                )
            } else {
                solve.copy(penalty = penalty).toSolveEntity(ownerId = ownerId)
            }

            solveDao.upsert(updated)
            if (ownerId != "guest") {
                syncOutboxDao.enqueue(updated.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json))
            }
        }
        syncTrigger?.invoke()
    }

    /**
     * Clear all active solves within the given puzzle mode scope (or all modes if mode is null).
     * Enqueues delete outbox mutations if ownerId != "guest", triggers sync,
     * and returns the complete list of deleted [SolveTime]s for undo snapshotting.
     */
    suspend fun clearAllSolvesInScope(
        mode: Mode?,
        ownerId: String = "guest"
    ): List<SolveTime> = withContext(ioDispatcher) {
        // Read and soft-delete in one transaction so a solve inserted between the two cannot
        // survive a "clear everything" and stay out of the undo snapshot.
        val deleted = runInTransaction {
            val existing = if (mode != null) {
                solveDao.getSolvesByEvent(ownerId = ownerId, event = mode.toEventString())
            } else {
                solveDao.getAllActiveSolvesForOwner(ownerId = ownerId)
            }
            if (existing.isEmpty()) return@runInTransaction emptyList()

            val nowIso = CubeTypeConverters.nowIso()
            solveDao.softDeleteAllChunked(existing.map { it.id }, deletedAt = nowIso, updatedAt = nowIso)
            if (ownerId != "guest") {
                syncOutboxDao.enqueueAll(existing.map { it.toDeleteMutation(ownerId = ownerId, clientTime = nowIso) })
            }
            existing.map { it.toSolveTime() }
        }
        if (deleted.isNotEmpty()) syncTrigger?.invoke()
        deleted
    }

    /**
     * Restore previously deleted solves (e.g. Snackbar Undo action). If a row with a given id
     * still exists (soft-deleted), its server [SolveEntity.version] is preserved and only
     * `deleted_at`/`updated_at`/content fields are refreshed, instead of resetting version to 0
     * and provoking a needless server conflict on next sync.
     */
    suspend fun restoreSolves(
        solves: List<SolveTime>,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        if (solves.isEmpty()) return@withContext

        val nowIso = CubeTypeConverters.nowIso()

        runInTransaction {
            val existingById = solveDao.getSolvesByIdsChunked(solves.map { it.id }).associateBy { it.id }
            val entities = solves.map { solve ->
                val existing = existingById[solve.id]
                if (existing != null) {
                    rewriteExisting(existing, solve, ownerId, sessionId = solve.sessionId, updatedAt = nowIso)
                } else {
                    solve.toSolveEntity(ownerId = ownerId, deletedAt = null)
                }
            }

            solveDao.upsertAll(entities)
            if (ownerId != "guest") {
                syncOutboxDao.enqueueAll(entities.map { it.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json) })
            }
        }
        syncTrigger?.invoke()
    }

    /**
     * App Time tracking methods (persisted in DataStore).
     */
    fun getAppTimeFlow(mode: Mode): Flow<Long> = context.solvesDataStore.data
        .map { preferences ->
            val key = longPreferencesKey("app_time_${mode.name}")
            preferences[key] ?: 0L
        }
        .distinctUntilChanged()

    suspend fun saveAppTime(mode: Mode, timeMillis: Long) {
        context.solvesDataStore.edit { preferences ->
            val key = longPreferencesKey("app_time_${mode.name}")
            preferences[key] = timeMillis
        }
    }
}
