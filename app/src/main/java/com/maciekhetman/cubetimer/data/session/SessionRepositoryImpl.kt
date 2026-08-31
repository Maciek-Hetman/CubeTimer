package com.maciekhetman.cubetimer.data.session

import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toDomain
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class SessionRepositoryImpl(
    private val database: CubeDatabase,
    private val sessionDao: SessionDao = database.sessionDao(),
    private val syncOutboxDao: SyncOutboxDao = database.syncOutboxDao(),
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
            val nowIso = Instant.now().toString()
            val entity = session.toEntity().copy(
                startedAt = if (session.startedAt.isNotBlank()) session.startedAt else nowIso,
                updatedAt = nowIso
            )
            sessionDao.insert(entity)

            if (entity.ownerId != "guest") {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = entity.ownerId,
                    entityType = "session",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = 0L,
                    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
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
        val nowIso = Instant.now().toString()
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
            val nowIso = Instant.now().toString()
            val entity = existing.copy(
                name = newName.trim(),
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = entity.ownerId,
                    entityType = "session",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = entity.version,
                    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
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
            val nowIso = Instant.now().toString()
            val entity = existing.copy(
                archived = true,
                endedAt = existing.endedAt ?: nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = entity.ownerId,
                    entityType = "session",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = entity.version,
                    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
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
            val nowIso = Instant.now().toString()
            val entity = existing.copy(
                archived = false,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = entity.ownerId,
                    entityType = "session",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = entity.version,
                    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
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

            val nowIso = Instant.now().toString()
            val entity = existing.copy(
                endedAt = nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                val payload = entity.toSyncPayload()
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = entity.ownerId,
                    entityType = "session",
                    entityId = entity.id,
                    action = "upsert",
                    baseVersion = entity.version,
                    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), payload),
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
            }
            entity.toDomain()
        }
        syncTrigger?.invoke()
        updated
    }

    override suspend fun deleteSession(
        id: String,
        ownerId: String
    ): Boolean = withContext(ioDispatcher) {
        val deleted = database.withTransaction {
            val existing = sessionDao.getSessionById(id) ?: return@withTransaction false
            val nowIso = Instant.now().toString()
            val entity = existing.copy(
                deletedAt = nowIso,
                updatedAt = nowIso
            )
            sessionDao.update(entity)

            if (entity.ownerId != "guest") {
                val mutation = SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = entity.ownerId,
                    entityType = "session",
                    entityId = entity.id,
                    action = "delete",
                    baseVersion = entity.version,
                    payloadJson = null,
                    clientTime = nowIso,
                    status = "pending"
                )
                syncOutboxDao.enqueue(mutation)
            }
            true
        }
        syncTrigger?.invoke()
        deleted
    }
}
