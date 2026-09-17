package com.maciekhetman.cubetimer.data.session

import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toDeleteMutation
import com.maciekhetman.cubetimer.data.local.mapper.toDomain
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class SessionRepositoryImpl(
    private val database: CubeDatabase,
    private val sessionDao: SessionDao = database.sessionDao(),
    private val syncOutboxDao: SyncOutboxDao = database.syncOutboxDao(),
    private val solveDao: SolveDao = database.solveDao(),
    private val json: Json = NetworkModule.json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val syncTrigger: (suspend () -> Unit)? = null
) : SessionRepository {

    override fun observeActiveSessions(ownerId: String, mode: Mode): Flow<List<Session>> {
        return sessionDao.observeActiveSessionsByEvent(ownerId, CubeTypeConverters.fromMode(mode))
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
    }

    override fun observeAllSessions(ownerId: String, mode: Mode): Flow<List<Session>> {
        return sessionDao.observeAllSessionsByEvent(ownerId, CubeTypeConverters.fromMode(mode))
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
    }

    override fun observeArchivedSessions(ownerId: String, mode: Mode): Flow<List<Session>> {
        return sessionDao.observeAllSessionsByEvent(ownerId, CubeTypeConverters.fromMode(mode))
            .map { list -> list.filter { it.archived }.map { it.toDomain() } }
            .distinctUntilChanged()
    }

    override fun observeSessionById(id: String): Flow<Session?> {
        return sessionDao.observeSessionById(id)
            .map { it?.toDomain() }
            .distinctUntilChanged()
    }

    override suspend fun getSessionById(id: String): Session? = withContext(ioDispatcher) {
        sessionDao.getSessionById(id)?.toDomain()
    }

    override suspend fun getOpenAutomaticSession(ownerId: String, mode: Mode): Session? = withContext(ioDispatcher) {
        sessionDao.getOpenAutomaticSession(ownerId, CubeTypeConverters.fromMode(mode))?.toDomain()
    }

    override suspend fun getActiveSessions(ownerId: String, mode: Mode): List<Session> = withContext(ioDispatcher) {
        sessionDao.getAllActiveSessionsForOwner(ownerId)
            .filter { it.event == CubeTypeConverters.fromMode(mode) && !it.archived }
            .map { it.toDomain() }
    }

    override suspend fun getSessionNamesWithPrefix(
        ownerId: String,
        mode: Mode,
        namePrefix: String
    ): List<String> = withContext(ioDispatcher) {
        sessionDao.getSessionNamesWithPrefix(ownerId, CubeTypeConverters.fromMode(mode), namePrefix)
    }

    override suspend fun createSession(session: Session): Session = withContext(ioDispatcher) {
        val created = database.withTransaction {
            val nowIso = CubeTypeConverters.nowIso()
            val entity = session.toEntity().copy(
                startedAt = if (session.startedAt.isNotBlank()) session.startedAt else nowIso,
                updatedAt = nowIso
            )
            sessionDao.insert(entity)

            if (entity.ownerId != "guest") {
                syncOutboxDao.enqueue(entity.toUpsertMutation(clientTime = nowIso, json = json))
            }
            entity.toDomain()
        }
        syncTrigger?.invoke()
        created
    }

    override suspend fun createManualSession(
        name: String,
        mode: Mode,
        ownerId: String
    ): Session = withContext(ioDispatcher) {
        val trimmedName = name.trim().ifBlank { "Session" }
        val nowIso = CubeTypeConverters.nowIso()
        val session = Session(
            id = UUID.randomUUID().toString(),
            ownerId = ownerId,
            name = trimmedName,
            event = mode,
            kind = SessionKind.MANUAL,
            archived = false,
            startedAt = nowIso,
            endedAt = null,
            version = 0L,
            updatedAt = nowIso,
            deletedAt = null
        )
        createSession(session)
    }

    override suspend fun renameSession(
        id: String,
        newName: String,
        ownerId: String
    ): Session? = withContext(ioDispatcher) {
        val updated = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction null
            val nowIso = CubeTypeConverters.nowIso()
            val entity = existing.copy(
                name = newName.trim(),
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                syncOutboxDao.enqueue(entity.toUpsertMutation(clientTime = nowIso, json = json))
            }
            entity.toDomain()
        }
        syncTrigger?.invoke()
        updated
    }

    override suspend fun archiveSession(
        id: String,
        ownerId: String
    ): Session? = withContext(ioDispatcher) {
        val updated = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction null
            val nowIso = CubeTypeConverters.nowIso()
            val entity = existing.copy(
                archived = true,
                endedAt = existing.endedAt ?: nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                syncOutboxDao.enqueue(entity.toUpsertMutation(clientTime = nowIso, json = json))
            }
            entity.toDomain()
        }
        syncTrigger?.invoke()
        updated
    }

    override suspend fun unarchiveSession(
        id: String,
        ownerId: String
    ): Session? = withContext(ioDispatcher) {
        val updated = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction null
            val nowIso = CubeTypeConverters.nowIso()
            val entity = existing.copy(
                archived = false,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                syncOutboxDao.enqueue(entity.toUpsertMutation(clientTime = nowIso, json = json))
            }
            entity.toDomain()
        }
        syncTrigger?.invoke()
        updated
    }

    override suspend fun closeSession(
        id: String,
        ownerId: String
    ): Session? = withContext(ioDispatcher) {
        val updated = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction null
            if (existing.endedAt != null) return@withTransaction existing.toDomain()

            val nowIso = CubeTypeConverters.nowIso()
            val entity = existing.copy(
                endedAt = nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                syncOutboxDao.enqueue(entity.toUpsertMutation(clientTime = nowIso, json = json))
            }
            entity.toDomain()
        }
        syncTrigger?.invoke()
        updated
    }

    /**
     * Soft-deletes the session AND cascades to soft-delete its still-active solves in the same
     * transaction, so a plain [deleteSession] call never orphans solves that would otherwise
     * remain active (and keep counting in stats) under a deleted session. Mirrors
     * [deleteSessionWithSolves] but without the undo snapshot.
     */
    override suspend fun deleteSession(
        id: String,
        ownerId: String
    ): Boolean = withContext(ioDispatcher) {
        val deleted = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction false
            val nowIso = CubeTypeConverters.nowIso()
            val entity = existing.copy(
                deletedAt = nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            val activeSolves = solveDao.getSolvesBySession(entity.ownerId, id)
            if (activeSolves.isNotEmpty()) {
                solveDao.softDeleteAll(activeSolves.map { it.id }, deletedAt = nowIso, updatedAt = nowIso)
            }

            if (entity.ownerId != "guest") {
                val mutations = buildList {
                    add(entity.toDeleteMutation(clientTime = nowIso))
                    activeSolves.forEach { add(it.toDeleteMutation(clientTime = nowIso)) }
                }
                syncOutboxDao.enqueueAll(mutations)
            }
            true
        }
        syncTrigger?.invoke()
        deleted
    }

    override suspend fun deleteSessionWithSolves(
        sessionId: String,
        ownerId: String
    ): DeletedSessionSnapshot? = withContext(ioDispatcher) {
        val snapshot = database.withTransaction {
            val existing = sessionDao.getSessionById(sessionId) ?: return@withTransaction null
            if (existing.deletedAt != null) return@withTransaction null

            val effectiveOwnerId = if (ownerId.isNotBlank() && ownerId != "guest") ownerId else existing.ownerId
            val nowIso = CubeTypeConverters.nowIso()

            // 1. Fetch all active non-deleted solves belonging to this session
            val activeSolvesEntities = solveDao.getSolvesBySession(effectiveOwnerId, sessionId)
            val domainSolves = activeSolvesEntities.map { it.toSolveTime() }
            val domainSession = existing.toDomain()

            // 2. Soft-delete the session entity
            val updatedSession = existing.copy(
                deletedAt = nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(updatedSession)

            // 3. Soft-delete all solves belonging to this session
            if (activeSolvesEntities.isNotEmpty()) {
                val solveIds = activeSolvesEntities.map { it.id }
                solveDao.softDeleteAll(solveIds, deletedAt = nowIso, updatedAt = nowIso)
            }

            // 4. Enqueue delete mutations in outbox if authenticated
            if (effectiveOwnerId != "guest") {
                val mutations = buildList {
                    add(updatedSession.toDeleteMutation(ownerId = effectiveOwnerId, clientTime = nowIso))
                    activeSolvesEntities.forEach { add(it.toDeleteMutation(ownerId = effectiveOwnerId, clientTime = nowIso)) }
                }
                syncOutboxDao.enqueueAll(mutations)
            }

            DeletedSessionSnapshot(
                session = domainSession,
                solves = domainSolves
            )
        }

        if (snapshot != null) {
            syncTrigger?.invoke()
        }
        snapshot
    }

    override suspend fun restoreSessionWithSolves(
        snapshot: DeletedSessionSnapshot,
        ownerId: String
    ): Unit = withContext(ioDispatcher) {
        val effectiveOwnerId = if (ownerId.isNotBlank() && ownerId != "guest") ownerId else snapshot.session.ownerId
        val nowIso = CubeTypeConverters.nowIso()

        database.withTransaction {
            // 1. Restore the session entity (deleted_at = null)
            val existingSession = sessionDao.getSessionById(snapshot.session.id)
            val sessionEntity = if (existingSession != null) {
                existingSession.copy(
                    deletedAt = null,
                    updatedAt = nowIso
                )
            } else {
                snapshot.session.toEntity().copy(
                    ownerId = effectiveOwnerId,
                    deletedAt = null,
                    updatedAt = nowIso
                )
            }
            sessionDao.upsert(sessionEntity)

            val outboxMutations = mutableListOf<SyncOutboxEntity>()
            if (effectiveOwnerId != "guest") {
                outboxMutations += sessionEntity.toUpsertMutation(ownerId = effectiveOwnerId, clientTime = nowIso, json = json)
            }

            // 2. Restore all solves from snapshot (deleted_at = null)
            if (snapshot.solves.isNotEmpty()) {
                val existingSolvesMap = solveDao.getSolvesByIds(snapshot.solves.map { it.id }).associateBy { it.id }
                val restoredSolvesEntities = snapshot.solves.map { solve ->
                    val existing = existingSolvesMap[solve.id]
                    if (existing != null) {
                        existing.copy(
                            deletedAt = null,
                            updatedAt = nowIso
                        )
                    } else {
                        solve.toSolveEntity(
                            ownerId = effectiveOwnerId,
                            sessionId = snapshot.session.id,
                            deletedAt = null
                        ).copy(updatedAt = nowIso)
                    }
                }
                solveDao.upsertAll(restoredSolvesEntities)

                if (effectiveOwnerId != "guest") {
                    restoredSolvesEntities.forEach { entity ->
                        outboxMutations += entity.toUpsertMutation(ownerId = effectiveOwnerId, clientTime = nowIso, json = json)
                    }
                }
            }

            if (outboxMutations.isNotEmpty()) {
                syncOutboxDao.enqueueAll(outboxMutations)
            }
        }

        syncTrigger?.invoke()
    }
}
