package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ConflictResolverTest {

    private lateinit var database: CubeDatabase
    private lateinit var conflictDao: ConflictDao
    private lateinit var sessionDao: SessionDao
    private lateinit var solveDao: SolveDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var resolver: ConflictResolver
    private val json: Json = NetworkModule.json

    private val testUserId = "user-test-uuid"

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conflictDao = database.conflictDao()
        sessionDao = database.sessionDao()
        solveDao = database.solveDao()
        syncOutboxDao = database.syncOutboxDao()

        resolver = ConflictResolverImpl(
            database = database,
            conflictDao = conflictDao,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao,
            json = json
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recordConflict_insertsUnresolvedConflictRecord() = runTest {
        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-1",
            entityType = "solve",
            entityId = "solve-1",
            serverVersion = 5L,
            serverUpdatedAt = "2026-08-30T10:00:00Z",
            localPayloadJson = """{"id":"solve-1","duration_ms":12000}""",
            serverPayloadJson = """{"id":"solve-1","duration_ms":11500,"version":5}""",
            errorMessage = "OCC mismatch"
        )

        assertNotNull(conflict.conflictId)
        assertFalse(conflict.resolved)

        val fetched = resolver.getConflictById(conflict.conflictId)
        assertNotNull(fetched)
        assertEquals(5L, fetched?.serverVersion)
        assertEquals("solve", fetched?.entityType)

        val unresolved = resolver.observeUnresolvedConflicts(testUserId).first()
        assertEquals(1, unresolved.size)
        assertEquals(conflict.conflictId, unresolved[0].conflictId)

        val count = resolver.observeUnresolvedCount(testUserId).first()
        assertEquals(1, count)
    }

    @Test
    fun resolveConflict_serverWins_forSession_overwritesLocalWithServerData() = runTest {
        val sessionId = "session-123"
        val localSession = SessionEntity(
            id = sessionId,
            ownerId = testUserId,
            name = "Local Name",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-08-30T09:00:00Z",
            version = 1L,
            updatedAt = "2026-08-30T09:00:00Z"
        )
        sessionDao.insert(localSession)

        val serverDto = SessionSnapshotDto(
            id = sessionId,
            name = "Server Updated Name",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-08-30T09:00:00Z",
            version = 3L,
            updatedAt = "2026-08-30T09:30:00Z"
        )
        val serverPayloadJson = json.encodeToString(SessionSnapshotDto.serializer(), serverDto)

        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-session",
            entityType = "session",
            entityId = sessionId,
            serverVersion = 3L,
            serverUpdatedAt = "2026-08-30T09:30:00Z",
            localPayloadJson = json.encodeToString(SessionSyncPayload.serializer(), localSession.toSyncPayload()),
            serverPayloadJson = serverPayloadJson
        )

        val resolved = resolver.resolveConflict(conflict.conflictId, ConflictPolicy.SERVER_WINS)
        assertTrue(resolved)

        val updatedSession = sessionDao.getSessionById(sessionId)
        assertNotNull(updatedSession)
        assertEquals("Server Updated Name", updatedSession?.name)
        assertEquals(3L, updatedSession?.version)

        val savedConflict = resolver.getConflictById(conflict.conflictId)
        assertTrue(savedConflict?.resolved == true)
        assertNotNull(savedConflict?.resolvedAt)
    }

    @Test
    fun resolveConflict_serverWins_forSolve_overwritesLocalWithServerData() = runTest {
        val solveId = "solve-123"
        val localSolve = SolveEntity(
            id = solveId,
            ownerId = testUserId,
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T09:00:00Z",
            version = 1L
        )
        solveDao.insert(localSolve)

        val serverDto = SolveSnapshotDto(
            id = solveId,
            durationMs = 17000L,
            penalty = "plus_two",
            solvedAt = "2026-08-30T09:00:00Z",
            version = 4L,
            updatedAt = "2026-08-30T09:45:00Z"
        )
        val serverPayloadJson = json.encodeToString(SolveSnapshotDto.serializer(), serverDto)

        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-solve",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 4L,
            serverUpdatedAt = "2026-08-30T09:45:00Z",
            localPayloadJson = json.encodeToString(SolveSyncPayload.serializer(), localSolve.toSyncPayload()),
            serverPayloadJson = serverPayloadJson
        )

        val resolved = resolver.resolveConflict(conflict.conflictId, ConflictPolicy.SERVER_WINS)
        assertTrue(resolved)

        val updatedSolve = solveDao.getSolveById(solveId)
        assertNotNull(updatedSolve)
        assertEquals(17000L, updatedSolve?.durationMs)
        assertEquals("plus_two", updatedSolve?.penalty)
        assertEquals(4L, updatedSolve?.version)

        val savedConflict = resolver.getConflictById(conflict.conflictId)
        assertTrue(savedConflict?.resolved == true)
    }

    @Test
    fun resolveConflict_localWins_forSession_enqueuesNewMutationTargetingServerVersion() = runTest {
        val sessionId = "session-lw"
        val localSession = SessionEntity(
            id = sessionId,
            ownerId = testUserId,
            name = "My Local Edits",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-08-30T09:00:00Z",
            version = 1L,
            updatedAt = "2026-08-30T10:00:00Z"
        )
        sessionDao.insert(localSession)

        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-sess-lw",
            entityType = "session",
            entityId = sessionId,
            serverVersion = 7L,
            serverUpdatedAt = "2026-08-30T09:50:00Z",
            localPayloadJson = json.encodeToString(SessionSyncPayload.serializer(), localSession.toSyncPayload()),
            serverPayloadJson = """{"id":"$sessionId","name":"Old Server Name","version":7}"""
        )

        val resolved = resolver.resolveConflict(conflict.conflictId, ConflictPolicy.LOCAL_WINS)
        assertTrue(resolved)

        val pending = syncOutboxDao.getPendingMutations(testUserId)
        assertEquals(1, pending.size)
        val mutation = pending[0]
        assertEquals(sessionId, mutation.entityId)
        assertEquals("session", mutation.entityType)
        assertEquals("upsert", mutation.action)
        assertEquals(7L, mutation.baseVersion)

        val savedConflict = resolver.getConflictById(conflict.conflictId)
        assertTrue(savedConflict?.resolved == true)
    }

    @Test
    fun resolveConflict_localWins_forSolve_enqueuesNewMutationTargetingServerVersion() = runTest {
        val solveId = "solve-lw"
        val localSolve = SolveEntity(
            id = solveId,
            ownerId = testUserId,
            durationMs = 9999L,
            penalty = "none",
            solvedAt = "2026-08-30T09:00:00Z",
            version = 2L,
            updatedAt = "2026-08-30T10:15:00Z"
        )
        solveDao.insert(localSolve)

        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-solve-lw",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 5L,
            serverUpdatedAt = "2026-08-30T10:10:00Z",
            localPayloadJson = json.encodeToString(SolveSyncPayload.serializer(), localSolve.toSyncPayload()),
            serverPayloadJson = """{"id":"$solveId","duration_ms":10500,"version":5}"""
        )

        val resolved = resolver.resolveConflict(conflict.conflictId, ConflictPolicy.LOCAL_WINS)
        assertTrue(resolved)

        val pending = syncOutboxDao.getPendingMutations(testUserId)
        assertEquals(1, pending.size)
        val mutation = pending[0]
        assertEquals(solveId, mutation.entityId)
        assertEquals("solve", mutation.entityType)
        assertEquals("upsert", mutation.action)
        assertEquals(5L, mutation.baseVersion)
    }

    @Test
    fun resolveConflict_lastWriteWins_selectsLocalWinsWhenLocalNewer() = runTest {
        val solveId = "solve-lww-local"
        val localSolve = SolveEntity(
            id = solveId,
            ownerId = testUserId,
            durationMs = 8888L,
            penalty = "none",
            solvedAt = "2026-08-30T09:00:00Z",
            version = 1L,
            updatedAt = "2026-08-30T10:30:00Z" // Newer than server
        )
        solveDao.insert(localSolve)

        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-lww-1",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 3L,
            serverUpdatedAt = "2026-08-30T10:00:00Z", // Older
            localPayloadJson = json.encodeToString(SolveSyncPayload.serializer(), localSolve.toSyncPayload()),
            serverPayloadJson = """{"id":"$solveId","duration_ms":9000,"version":3,"updated_at":"2026-08-30T10:00:00Z"}"""
        )

        val resolved = resolver.resolveConflict(conflict.conflictId, ConflictPolicy.LAST_WRITE_WINS)
        assertTrue(resolved)

        // Should have enqueued local mutation with server base version 3
        val pending = syncOutboxDao.getPendingMutations(testUserId)
        assertEquals(1, pending.size)
        assertEquals(3L, pending[0].baseVersion)
    }

    @Test
    fun resolveConflict_lastWriteWins_selectsServerWinsWhenServerNewer() = runTest {
        val solveId = "solve-lww-server"
        val localSolve = SolveEntity(
            id = solveId,
            ownerId = testUserId,
            durationMs = 8888L,
            penalty = "none",
            solvedAt = "2026-08-30T09:00:00Z",
            version = 1L,
            updatedAt = "2026-08-30T09:30:00Z" // Older than server
        )
        solveDao.insert(localSolve)

        val serverDto = SolveSnapshotDto(
            id = solveId,
            durationMs = 7777L,
            penalty = "none",
            solvedAt = "2026-08-30T09:00:00Z",
            version = 3L,
            updatedAt = "2026-08-30T10:00:00Z" // Newer
        )

        val conflict = resolver.recordConflict(
            ownerId = testUserId,
            mutationId = "mut-lww-2",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 3L,
            serverUpdatedAt = "2026-08-30T10:00:00Z",
            localPayloadJson = json.encodeToString(SolveSyncPayload.serializer(), localSolve.toSyncPayload()),
            serverPayloadJson = json.encodeToString(SolveSnapshotDto.serializer(), serverDto)
        )

        val resolved = resolver.resolveConflict(conflict.conflictId, ConflictPolicy.LAST_WRITE_WINS)
        assertTrue(resolved)

        val updated = solveDao.getSolveById(solveId)
        assertEquals(7777L, updated?.durationMs)
        assertEquals(3L, updated?.version)

        val pending = syncOutboxDao.getPendingMutations(testUserId)
        assertEquals(0, pending.size)
    }
}
