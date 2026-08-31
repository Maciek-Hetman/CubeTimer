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
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toDbString
import com.maciekhetman.cubetimer.data.local.mapper.toEventString
import com.maciekhetman.cubetimer.data.local.mapper.toSolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

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
     * Backwards-compatible reactive stream of all active (non-deleted) guest solves.
     */
    val solvesFlow: Flow<List<SolveTime>> = solveDao.observeAllSolves(ownerId = "guest")
        .map { entities -> entities.map { it.toSolveTime() } }

    /**
     * Observe all active solves for a specific owner.
     */
    fun getAllSolvesFlow(ownerId: String = "guest"): Flow<List<SolveTime>> {
        return solveDao.observeAllSolves(ownerId = ownerId)
            .map { entities -> entities.map { it.toSolveTime() } }
    }

    /**
     * One-shot fetch of all active solves for a specific owner.
     */
    suspend fun getAllActiveSolves(ownerId: String = "guest"): List<SolveTime> = withContext(ioDispatcher) {
        solveDao.getAllActiveSolvesForOwner(ownerId).map { it.toSolveTime() }
    }

    /**
     * Observe active solves for a specific puzzle mode / event and owner.
     */
    fun getSolvesFlow(mode: Mode, ownerId: String = "guest"): Flow<List<SolveTime>> {
        return solveDao.observeSolvesByEvent(ownerId = ownerId, event = mode.toEventString())
            .map { entities -> entities.map { it.toSolveTime() } }
    }

    /**
     * Observe active solves associated with a specific session and owner.
     */
    fun getSolvesBySessionFlow(sessionId: String, ownerId: String = "guest"): Flow<List<SolveTime>> {
        return solveDao.observeSolvesBySession(ownerId = ownerId, sessionId = sessionId)
            .map { entities -> entities.map { it.toSolveTime() } }
    }

    /**
     * Save a single solve with session association and transactional outbox mutation dispatch.
     */
    suspend fun saveSolve(
        solve: SolveTime,
        ownerId: String = "guest",
        sessionId: String? = solve.sessionId
    ) = withContext(ioDispatcher) {
        val nowIso = Instant.now().toString()
        val entity = solve.toSolveEntity(ownerId = ownerId, sessionId = sessionId ?: solve.sessionId)
        solveDao.upsert(entity)

        if (ownerId != "guest") {
            val payload = entity.toSyncPayload()
            val mutation = SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                entityType = "solve",
                entityId = entity.id,
                action = "upsert",
                baseVersion = entity.version,
                payloadJson = json.encodeToString(SolveSyncPayload.serializer(), payload),
                clientTime = nowIso,
                status = "pending"
            )
            syncOutboxDao.enqueue(mutation)
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
     */
    suspend fun deleteSolves(
        solves: List<SolveTime>,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        if (solves.isEmpty()) return@withContext
        val nowIso = Instant.now().toString()
        val ids = solves.map { it.id }
        solveDao.softDeleteAll(ids, deletedAt = nowIso, updatedAt = nowIso)

        if (ownerId != "guest") {
            for (solve in solves) {
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = ownerId,
                    entityType = "solve",
                    entityId = solve.id,
                    action = "delete",
                    baseVersion = 0L,
                    payloadJson = null,
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
            }
        }
        syncTrigger?.invoke()
    }

    /**
     * Update penalty for an existing solve and enqueue upsert mutation if authenticated.
     */
    suspend fun updateSolvePenalty(
        solve: SolveTime,
        penalty: Penalty,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        val existing = solveDao.getSolveById(solve.id)
        val nowIso = Instant.now().toString()
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
            val payload = updated.toSyncPayload()
            val mutation = SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                entityType = "solve",
                entityId = updated.id,
                action = "upsert",
                baseVersion = existing?.version ?: 0L,
                payloadJson = json.encodeToString(SolveSyncPayload.serializer(), payload),
                clientTime = nowIso,
                status = "pending"
            )
            syncOutboxDao.enqueue(mutation)
        }
        syncTrigger?.invoke()
    }

    /**
     * Backwards-compatible batch save/sync solves method.
     */
    suspend fun saveSolves(
        solves: List<SolveTime>,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        val nowIso = Instant.now().toString()
        if (solves.isEmpty()) {
            val existing = solveDao.getAllActiveSolvesForOwner(ownerId)
            if (existing.isNotEmpty()) {
                solveDao.softDeleteAll(existing.map { it.id }, deletedAt = nowIso, updatedAt = nowIso)
                if (ownerId != "guest") {
                    for (item in existing) {
                        val mutation = SyncOutboxEntity(
                            id = UUID.randomUUID().toString(),
                            ownerId = ownerId,
                            entityType = "solve",
                            entityId = item.id,
                            action = "delete",
                            baseVersion = item.version,
                            payloadJson = null,
                            clientTime = nowIso,
                            status = "pending"
                        )
                        syncOutboxDao.enqueue(mutation)
                    }
                }
            }
            syncTrigger?.invoke()
            return@withContext
        }

        val currentSolves = solveDao.getAllActiveSolvesForOwner(ownerId)
        val currentIds = currentSolves.map { it.id }.toSet()
        val newIds = solves.map { it.id }.toSet()

        val removedIds = currentIds - newIds
        if (removedIds.isNotEmpty()) {
            solveDao.softDeleteAll(removedIds.toList(), deletedAt = nowIso, updatedAt = nowIso)
            if (ownerId != "guest") {
                val removedEntities = currentSolves.filter { it.id in removedIds }
                for (item in removedEntities) {
                    val mutation = SyncOutboxEntity(
                        id = UUID.randomUUID().toString(),
                        ownerId = ownerId,
                        entityType = "solve",
                        entityId = item.id,
                        action = "delete",
                        baseVersion = item.version,
                        payloadJson = null,
                        clientTime = nowIso,
                        status = "pending"
                    )
                    syncOutboxDao.enqueue(mutation)
                }
            }
        }

        val entities = solves.map { it.toSolveEntity(ownerId = ownerId) }
        if (database != null) {
            database.withTransaction {
                solveDao.upsertAll(entities)
            }
        } else {
            solveDao.upsertAll(entities)
        }

        if (ownerId != "guest") {
            for (entity in entities) {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = ownerId,
                    entityType = "solve",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = entity.version,
                    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
            }
        }
        syncTrigger?.invoke()
    }

    /**
     * Clear all solves for owner.
     */
    suspend fun clearAllSolves(ownerId: String = "guest") = withContext(ioDispatcher) {
        val nowIso = Instant.now().toString()
        val existing = solveDao.getAllActiveSolvesForOwner(ownerId)
        if (existing.isNotEmpty()) {
            if (database != null) {
                database.withTransaction {
                    solveDao.softDeleteAll(existing.map { it.id }, deletedAt = nowIso, updatedAt = nowIso)
                }
            } else {
                solveDao.softDeleteAll(existing.map { it.id }, deletedAt = nowIso, updatedAt = nowIso)
            }
            if (ownerId != "guest") {
                for (item in existing) {
                    val mutation = SyncOutboxEntity(
                        id = UUID.randomUUID().toString(),
                        ownerId = ownerId,
                        entityType = "solve",
                        entityId = item.id,
                        action = "delete",
                        baseVersion = item.version,
                        payloadJson = null,
                        clientTime = nowIso,
                        status = "pending"
                    )
                    syncOutboxDao.enqueue(mutation)
                }
            }
        }
        syncTrigger?.invoke()
    }

    /**
     * Restore previous solves (e.g. Snackbar Undo action).
     */
    suspend fun restoreSolves(
        solves: List<SolveTime>,
        ownerId: String = "guest"
    ) = withContext(ioDispatcher) {
        val entities = solves.map { it.toSolveEntity(ownerId = ownerId, deletedAt = null) }
        if (database != null) {
            database.withTransaction {
                solveDao.upsertAll(entities)
            }
        } else {
            solveDao.upsertAll(entities)
        }
        if (ownerId != "guest") {
            val nowIso = Instant.now().toString()
            for (entity in entities) {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = ownerId,
                    entityType = "solve",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = entity.version,
                    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
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

    // Session Management Delegation
    fun getSessionsFlow(ownerId: String = "guest", event: String): Flow<List<SessionEntity>> {
        return sessionDao.observeActiveSessionsByEvent(ownerId, event)
    }

    suspend fun getOpenAutomaticSession(ownerId: String = "guest", event: String): SessionEntity? {
        return sessionDao.getOpenAutomaticSession(ownerId, event)
    }

    suspend fun saveSession(session: SessionEntity) = withContext(ioDispatcher) {
        sessionDao.insert(session)
    }

    suspend fun deleteSession(sessionId: String) = withContext(ioDispatcher) {
        val nowIso = Instant.now().toString()
        sessionDao.softDelete(sessionId, deletedAt = nowIso, updatedAt = nowIso)
    }
}
