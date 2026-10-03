package com.maciekhetman.cubetimer.data.session

import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.dao.getSolvesByIdsChunked
import com.maciekhetman.cubetimer.data.local.dao.softDeleteAllChunked
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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

    override suspend fun getSessionById(id: String): Session? = withContext(ioDispatcher) {
        sessionDao.getSessionById(id)?.toDomain()
    }

    override suspend fun getOpenAutomaticSession(ownerId: String, mode: Mode): Session? = withContext(ioDispatcher) {
        sessionDao.getOpenAutomaticSession(ownerId, CubeTypeConverters.fromMode(mode))?.toDomain()
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

    override suspend fun closeSession(
        id: String,
        ownerId: String
    ): Session? = withContext(ioDispatcher) {
        var changed = false
        val updated = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction null
            if (existing.ownerId != ownerId) return@withTransaction null
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
            changed = true
            entity.toDomain()
        }
        if (changed) syncTrigger?.invoke()
        updated
    }

    override suspend fun deleteSessionWithSolves(
        sessionId: String,
        ownerId: String
    ): DeletedSessionSnapshot? = withContext(ioDispatcher) {
        val snapshot = database.withTransaction {
            val existing = sessionDao.getSessionById(sessionId) ?: return@withTransaction null
            if (existing.deletedAt != null || existing.ownerId != ownerId) return@withTransaction null

            val nowIso = CubeTypeConverters.nowIso()

            // 1. Fetch all active non-deleted solves belonging to this session
            val activeSolvesEntities = solveDao.getSolvesBySession(ownerId, sessionId)
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
                solveDao.softDeleteAllChunked(solveIds, deletedAt = nowIso, updatedAt = nowIso)
            }

            // 4. Enqueue delete mutations in outbox if authenticated
            if (ownerId != "guest") {
                val mutations = buildList {
                    add(updatedSession.toDeleteMutation(ownerId = ownerId, clientTime = nowIso))
                    activeSolvesEntities.forEach { add(it.toDeleteMutation(ownerId = ownerId, clientTime = nowIso)) }
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
        if (snapshot.session.ownerId != ownerId) return@withContext
        val nowIso = CubeTypeConverters.nowIso()
        var restored = false

        database.withTransaction {
            // 1. Restore the session entity (deleted_at = null). Refuse to overwrite a row that
            // belongs to someone else: session ids are global.
            val existingSession = sessionDao.getSessionById(snapshot.session.id)
            if (existingSession != null && existingSession.ownerId != ownerId) return@withTransaction
            val sessionEntity = if (existingSession != null) {
                existingSession.copy(
                    deletedAt = null,
                    updatedAt = nowIso
                )
            } else {
                snapshot.session.toEntity().copy(
                    ownerId = ownerId,
                    deletedAt = null,
                    updatedAt = nowIso
                )
            }
            sessionDao.upsert(sessionEntity)

            val outboxMutations = mutableListOf<SyncOutboxEntity>()
            if (ownerId != "guest") {
                outboxMutations += sessionEntity.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json)
            }

            // 2. Restore all solves from snapshot (deleted_at = null). A row owned by someone else
            // is left alone; its id cannot be taken over.
            if (snapshot.solves.isNotEmpty()) {
                val existingSolvesMap = solveDao.getSolvesByIdsChunked(snapshot.solves.map { it.id }).associateBy { it.id }
                val restoredSolvesEntities = snapshot.solves.mapNotNull { solve ->
                    val existing = existingSolvesMap[solve.id]
                    when {
                        existing == null -> solve.toSolveEntity(
                            ownerId = ownerId,
                            sessionId = snapshot.session.id,
                            deletedAt = null
                        ).copy(updatedAt = nowIso)
                        existing.ownerId != ownerId -> null
                        else -> existing.copy(deletedAt = null, updatedAt = nowIso)
                    }
                }
                if (restoredSolvesEntities.isNotEmpty()) {
                    solveDao.upsertAll(restoredSolvesEntities)
                }

                if (ownerId != "guest") {
                    restoredSolvesEntities.forEach { entity ->
                        outboxMutations += entity.toUpsertMutation(ownerId = ownerId, clientTime = nowIso, json = json)
                    }
                }
            }

            if (outboxMutations.isNotEmpty()) {
                syncOutboxDao.enqueueAll(outboxMutations)
            }
            restored = true
        }

        if (restored) syncTrigger?.invoke()
    }
}
