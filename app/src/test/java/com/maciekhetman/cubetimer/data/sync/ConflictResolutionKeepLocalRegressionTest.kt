package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.data.local.mapper.toSyncPayload
import com.maciekhetman.cubetimer.data.local.mapper.toUpsertMutation
import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.ChangeDto
import com.maciekhetman.cubetimer.data.remote.dto.MutationOutcomeDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest
import com.maciekhetman.cubetimer.data.remote.dto.SnapshotResponse
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.data.remote.dto.SyncResponse
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

/**
 * Regression suite for conflict resolution after the sync engine has applied the same response's
 * `changes` over the conflicting entity.
 *
 * On a conflict the engine deletes the conflicting outbox mutation and records the user's payload
 * on the conflict; the response's `changes` then carry the server's newer copy of that entity,
 * which (nothing being queued any more) overwrites the Room row. "Keep local" used to re-send the
 * current row - i.e. the server's data - losing the user's edit; "keep server" used to write the
 * conflict's snapshot even over newer server versions that reached the row later.
 */
@RunWith(RobolectricTestRunner::class)
class ConflictResolutionKeepLocalRegressionTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var conflictDao: ConflictDao
    private lateinit var resolver: ConflictResolverImpl
    private lateinit var fakeApi: FakeSyncApiClient
    private lateinit var syncEngine: SyncEngineImpl
    private lateinit var solvesRepository: SolvesRepository
    private val json: Json = NetworkModule.json

    private val user = User(
        id = "user-keep-local",
        email = "cuber@example.com",
        userRole = UserRole.USER,
        emailVerified = true,
        displayName = "Cuber"
    )
    private val owner = user.id

    private val sessionId = "sess-1"
    private val solveId = "solve-1"
    private val solvedAt = "2026-09-01T09:00:00.000Z"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        // Production opens the database with foreign keys enforced; make sure this one does too
        // so a dangling solves.session_id fails the (deferred) check at commit, as on device.
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")
        solveDao = database.solveDao()
        sessionDao = database.sessionDao()
        syncOutboxDao = database.syncOutboxDao()
        conflictDao = database.conflictDao()

        resolver = ConflictResolverImpl(database, conflictDao, solveDao, sessionDao, syncOutboxDao, json)
        fakeApi = FakeSyncApiClient()
        syncEngine = SyncEngineImpl(
            apiClient = fakeApi,
            tokenStorage = FakeTokenStorage(owner),
            database = database,
            authManager = FakeAuthManager(AuthState.Authenticated(user)),
            conflictResolver = resolver,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao,
            syncMetadataDao = database.syncMetadataDao(),
            conflictDao = conflictDao,
            json = json
        )
        // database = null: no DataStore migration kicked off in the background; writes still go
        // through the repository's real row + outbox path.
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao,
            database = null,
            json = json
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------------------------------
    // Keep local
    // ---------------------------------------------------------------------------------------

    @Test
    fun keepLocal_afterServerChangeOverwroteRow_reassertsUsersEditAgainstNewestServerVersion() = runTest {
        insertSession()
        val original = insertSolve(durationMs = 10_000L, timingDevice = "external_timer")

        // The user gives the solve a +2 (row + outbox upsert with base_version 1).
        solvesRepository.updateSolvePenalty(original.toSolveTime(), Penalty.PLUS_TWO, ownerId = owner)

        // Meanwhile another device changed the time: the server is at v2 with no penalty.
        val server = serverSolve(version = 2L, durationMs = 11_500L, updatedAt = "2026-09-01T10:00:00.000Z")
        fakeApi.respond = { request ->
            val sent = request.mutations.single()
            SyncResponse(
                outcomes = listOf(conflictOutcome(sent.id, 2L, current = json.encodeToJsonElement(SolveSnapshotDto.serializer(), server))),
                changes = listOf(solveChange(server, cursor = 10L)),
                nextCursor = 10L
            )
        }

        val result = syncEngine.sync(owner)
        assertTrue(result is SyncResult.Success)
        assertEquals(1, (result as SyncResult.Success).conflictsRecorded)

        // Precondition of the bug: the server's copy has overwritten the user's edit locally.
        val overwritten = solveDao.getSolveById(solveId)!!
        assertEquals(11_500L, overwritten.durationMs)
        assertEquals("none", overwritten.penalty)
        assertEquals(2L, overwritten.version)
        assertEquals(0, syncOutboxDao.countPending(owner))

        val conflict = conflictDao.getAll(owner).single()
        assertFalse(conflict.resolved)
        assertEquals(2L, conflict.serverVersion)

        assertTrue(syncEngine.resolveConflictKeepLocal(conflict.conflictId))

        // The queued upsert carries the user's version, based on the server's v2.
        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals("solve", queued.entityType)
        assertEquals(solveId, queued.entityId)
        assertEquals("upsert", queued.action)
        assertEquals(2L, queued.baseVersion)
        val payload = json.decodeFromString(SolveSyncPayload.serializer(), queued.payloadJson!!)
        assertEquals("plus_two", payload.penalty)
        assertEquals(10_000L, payload.durationMs)
        assertEquals(sessionId, payload.sessionId)
        assertEquals(solvedAt, payload.solvedAt)
        assertEquals("R U R' U'", payload.scramble)
        assertEquals("3x3", payload.event)
        assertEquals("external_timer", payload.timingDevice)

        // Room shows what was kept, at the known server version.
        val kept = solveDao.getSolveById(solveId)!!
        assertEquals("plus_two", kept.penalty)
        assertEquals(10_000L, kept.durationMs)
        assertEquals(sessionId, kept.sessionId)
        assertEquals("external_timer", kept.timingDevice)
        assertEquals(owner, kept.ownerId)
        assertEquals(2L, kept.version)
        assertNull(kept.deletedAt)

        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)

        // And the next sync pushes exactly that, against v2.
        fakeApi.respond = { request ->
            SyncResponse(
                outcomes = request.mutations.map { MutationOutcomeDto(mutationId = it.id, status = "accepted", version = 3L) },
                nextCursor = 11L
            )
        }
        assertTrue(syncEngine.sync(owner) is SyncResult.Success)
        val pushed = fakeApi.syncRequests.last().mutations.single()
        assertEquals(2L, pushed.baseVersion)
        assertEquals("plus_two", json.decodeFromJsonElement(SolveSyncPayload.serializer(), pushed.data!!).penalty)
        assertEquals(3L, solveDao.getSolveById(solveId)!!.version)
        assertEquals("plus_two", solveDao.getSolveById(solveId)!!.penalty)
        assertEquals(0, syncOutboxDao.countPending(owner))
    }

    @Test
    fun keepLocal_whenConflictingMutationWasDelete_softDeletesAndRequeuesDelete() = runTest {
        insertSession()
        insertSolve(durationMs = 10_000L)

        // The user deletes the solve (soft delete + outbox delete with base_version 1) ...
        solvesRepository.deleteSolvesByIds(listOf(solveId), ownerId = owner)
        assertNotNull(solveDao.getSolveById(solveId)!!.deletedAt)

        // ... while another device edited it: the server's v2 resurrects the row locally.
        val server = serverSolve(version = 2L, durationMs = 12_000L, updatedAt = "2026-09-01T10:00:00.000Z")
        fakeApi.respond = { request ->
            SyncResponse(
                outcomes = listOf(conflictOutcome(request.mutations.single().id, 2L, current = json.encodeToJsonElement(SolveSnapshotDto.serializer(), server))),
                changes = listOf(solveChange(server, cursor = 10L)),
                nextCursor = 10L
            )
        }
        assertTrue(syncEngine.sync(owner) is SyncResult.Success)
        assertNull(solveDao.getSolveById(solveId)!!.deletedAt)

        val conflict = conflictDao.getAll(owner).single()
        assertNull(conflict.localPayloadJson)

        assertTrue(syncEngine.resolveConflictKeepLocal(conflict.conflictId))

        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals("delete", queued.action)
        assertEquals(solveId, queued.entityId)
        assertEquals(2L, queued.baseVersion)
        assertNull(queued.payloadJson)

        val row = solveDao.getSolveById(solveId)!!
        assertNotNull(row.deletedAt)
        assertEquals(2L, row.version)
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    @Test
    fun keepLocal_withNewerQueuedEdit_rebasesItInsteadOfQueuingTheStaleConflictPayload() = runTest {
        insertSession()
        val original = insertSolve(durationMs = 10_000L)

        solvesRepository.updateSolvePenalty(original.toSolveTime(), Penalty.PLUS_TWO, ownerId = owner)

        val server = serverSolve(version = 2L, durationMs = 11_500L, updatedAt = "2026-09-01T10:00:00.000Z")
        var calls = 0
        fakeApi.respond = { request ->
            if (++calls == 1) {
                // While the +2 is in flight, the user changes their mind and makes it a DNF.
                val current = solveDao.getSolveById(solveId)!!
                solvesRepository.updateSolvePenalty(current.toSolveTime(), Penalty.DNF, ownerId = owner)
                SyncResponse(
                    outcomes = listOf(conflictOutcome(request.mutations.single().id, 2L, current = json.encodeToJsonElement(SolveSnapshotDto.serializer(), server))),
                    changes = listOf(solveChange(server, cursor = 10L)),
                    nextCursor = 10L
                )
            } else {
                // Leave anything sent later unanswered: it goes back to pending untouched.
                SyncResponse(nextCursor = 10L)
            }
        }
        assertTrue(syncEngine.sync(owner) is SyncResult.Success)

        // The DNF edit is queued (base 1) and protected the row from the server change.
        val dnfMutation = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals(1L, dnfMutation.baseVersion)
        assertEquals("dnf", solveDao.getSolveById(solveId)!!.penalty)

        val conflict = conflictDao.getAll(owner).single()
        assertTrue(syncEngine.resolveConflictKeepLocal(conflict.conflictId))

        // No second (stale, +2) mutation: the queued DNF edit is rebased onto the server's v2.
        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals(dnfMutation.id, queued.id)
        assertEquals(2L, queued.baseVersion)
        assertEquals("dnf", json.decodeFromString(SolveSyncPayload.serializer(), queued.payloadJson!!).penalty)

        val row = solveDao.getSolveById(solveId)!!
        assertEquals("dnf", row.penalty)
        assertEquals(10_000L, row.durationMs)
        assertEquals(2L, row.version)
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    @Test
    fun keepLocal_forSession_reassertsUsersVersionWithoutDetachingItsSolves() = runTest {
        val original = insertSession(name = "1 sep 2026 morning")
        insertSolve(durationMs = 10_000L)

        // The user's edit: the session was closed on this device.
        val closed = original.copy(endedAt = "2026-09-01T09:30:00.000Z", updatedAt = "2026-09-01T09:30:00.000Z")
        sessionDao.update(closed)
        syncOutboxDao.enqueue(closed.toUpsertMutation(ownerId = owner, clientTime = "2026-09-01T09:30:00.000Z", json = json))

        // Another client renamed and archived it: server v2.
        val serverSession = SessionSnapshotDto(
            id = sessionId,
            name = "Renamed elsewhere",
            event = "3x3",
            kind = "automatic",
            startedAt = original.startedAt,
            endedAt = null,
            archived = true,
            version = 2L,
            updatedAt = "2026-09-01T10:00:00.000Z"
        )
        val serverJson = json.encodeToJsonElement(SessionSnapshotDto.serializer(), serverSession)
        fakeApi.respond = { request ->
            SyncResponse(
                outcomes = listOf(conflictOutcome(request.mutations.single().id, 2L, current = serverJson)),
                changes = listOf(
                    ChangeDto(
                        cursor = 10L,
                        entity = "session",
                        entityId = sessionId,
                        operation = "upsert",
                        version = 2L,
                        data = serverJson,
                        changedAt = serverSession.updatedAt
                    )
                ),
                nextCursor = 10L
            )
        }
        assertTrue(syncEngine.sync(owner) is SyncResult.Success)
        assertEquals("Renamed elsewhere", sessionDao.getSessionById(sessionId)!!.name)

        val conflict = conflictDao.getAll(owner).single()
        assertTrue(syncEngine.resolveConflictKeepLocal(conflict.conflictId))

        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals("session", queued.entityType)
        assertEquals("upsert", queued.action)
        assertEquals(2L, queued.baseVersion)
        val payload = json.decodeFromString(SessionSyncPayload.serializer(), queued.payloadJson!!)
        assertEquals("1 sep 2026 morning", payload.name)
        assertEquals("2026-09-01T09:30:00.000Z", payload.endedAt)
        assertFalse(payload.archived)
        assertEquals("automatic", payload.kind)

        val kept = sessionDao.getSessionById(sessionId)!!
        assertEquals("1 sep 2026 morning", kept.name)
        assertEquals("2026-09-01T09:30:00.000Z", kept.endedAt)
        assertFalse(kept.archived)
        assertEquals(2L, kept.version)
        assertNull(kept.deletedAt)
        assertEquals(owner, kept.ownerId)

        // Written in place: the session's solves still point at it.
        assertEquals(sessionId, solveDao.getSolveById(solveId)!!.sessionId)
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    @Test
    fun keepLocal_solvePayloadNamingMissingSession_keepsRowsSessionAndCommits() = runTest {
        // Only meaningful while the deferred FK is enforced (it would fail the commit otherwise).
        database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        insertSession()
        insertSolve(durationMs = 10_000L, version = 2L)
        val userPayload = SolveSyncPayload(
            id = solveId,
            sessionId = "session-gone",
            durationMs = 9_000L,
            penalty = "plus_two",
            solvedAt = solvedAt,
            scramble = "R U R' U'",
            event = "3x3"
        )
        val conflict = recordSolveConflict(serverVersion = 2L, localPayload = userPayload)

        assertTrue(resolver.resolveKeepLocal(conflict.conflictId))

        val kept = solveDao.getSolveById(solveId)!!
        assertEquals(sessionId, kept.sessionId)
        assertEquals("plus_two", kept.penalty)
        assertEquals(9_000L, kept.durationMs)
        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals(sessionId, json.decodeFromString(SolveSyncPayload.serializer(), queued.payloadJson!!).sessionId)
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    @Test
    fun keepLocal_solvePayloadNamingMissingSession_withoutLocalRow_keepsNoSession() = runTest {
        val userPayload = SolveSyncPayload(
            id = solveId,
            sessionId = "session-gone",
            durationMs = 9_000L,
            penalty = "dnf",
            solvedAt = solvedAt,
            scramble = "R U R' U'",
            event = "3x3"
        )
        val conflict = recordSolveConflict(serverVersion = 4L, localPayload = userPayload)

        assertTrue(resolver.resolveKeepLocal(conflict.conflictId))

        val kept = solveDao.getSolveById(solveId)!!
        assertNull(kept.sessionId)
        assertEquals("dnf", kept.penalty)
        assertEquals(4L, kept.version)
        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals(4L, queued.baseVersion)
        assertNull(json.decodeFromString(SolveSyncPayload.serializer(), queued.payloadJson!!).sessionId)
    }

    @Test
    fun keepLocal_undecodablePayload_fallsBackToCurrentRowAtNewestKnownVersion() = runTest {
        insertSession()
        insertSolve(durationMs = 10_000L, version = 3L, penalty = "dnf")
        val conflict = resolver.recordConflict(
            ownerId = owner,
            mutationId = "mut-garbled",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 2L,
            serverUpdatedAt = null,
            localPayloadJson = "{not json",
            serverPayloadJson = null
        )

        assertTrue(resolver.resolveKeepLocal(conflict.conflictId))

        val queued = syncOutboxDao.getAllPendingForOwner(owner).single()
        assertEquals("upsert", queued.action)
        assertEquals(3L, queued.baseVersion)
        assertEquals("dnf", json.decodeFromString(SolveSyncPayload.serializer(), queued.payloadJson!!).penalty)
        assertEquals(3L, solveDao.getSolveById(solveId)!!.version)
    }

    // ---------------------------------------------------------------------------------------
    // Keep server
    // ---------------------------------------------------------------------------------------

    @Test
    fun keepServer_whenRowAlreadyMovedPastConflictVersion_leavesRowAndResolves() = runTest {
        insertSession()
        val original = insertSolve(durationMs = 10_000L)
        solvesRepository.updateSolvePenalty(original.toSolveTime(), Penalty.PLUS_TWO, ownerId = owner)

        // First sync: conflict against v2 (and the v2 change).
        val v2 = serverSolve(version = 2L, durationMs = 11_500L, updatedAt = "2026-09-01T10:00:00.000Z")
        fakeApi.respond = { request ->
            SyncResponse(
                outcomes = listOf(conflictOutcome(request.mutations.single().id, 2L, current = json.encodeToJsonElement(SolveSnapshotDto.serializer(), v2))),
                changes = listOf(solveChange(v2, cursor = 10L)),
                nextCursor = 10L
            )
        }
        assertTrue(syncEngine.sync(owner) is SyncResult.Success)
        val conflict = conflictDao.getAll(owner).single()

        // Before the user decides, the other device edits again: v3 reaches the row.
        val v3 = serverSolve(version = 3L, durationMs = 12_345L, updatedAt = "2026-09-01T11:00:00.000Z", penalty = "dnf")
        fakeApi.respond = { SyncResponse(changes = listOf(solveChange(v3, cursor = 11L)), nextCursor = 11L) }
        assertTrue(syncEngine.sync(owner) is SyncResult.Success)
        assertEquals(3L, solveDao.getSolveById(solveId)!!.version)

        assertTrue(syncEngine.resolveConflictKeepServer(conflict.conflictId))

        // The stale v2 snapshot must not regress the row: the cursor is past v3 already.
        val row = solveDao.getSolveById(solveId)!!
        assertEquals(3L, row.version)
        assertEquals(12_345L, row.durationMs)
        assertEquals("dnf", row.penalty)
        assertEquals(0, syncOutboxDao.countPending(owner))
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    @Test
    fun keepServer_whenRowNotPastConflictVersion_stillAppliesSnapshot() = runTest {
        insertSession()
        insertSolve(durationMs = 10_000L, version = 1L, penalty = "plus_two")
        val snapshot = serverSolve(version = 2L, durationMs = 11_500L, updatedAt = "2026-09-01T10:00:00.000Z")
        val conflict = resolver.recordConflict(
            ownerId = owner,
            mutationId = "mut-1",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 2L,
            serverUpdatedAt = null,
            localPayloadJson = json.encodeToString(SolveSyncPayload.serializer(), solveDao.getSolveById(solveId)!!.toSyncPayload()),
            serverPayloadJson = json.encodeToString(SolveSnapshotDto.serializer(), snapshot)
        )

        assertTrue(resolver.resolveKeepServer(conflict.conflictId))

        val row = solveDao.getSolveById(solveId)!!
        assertEquals(2L, row.version)
        assertEquals(11_500L, row.durationMs)
        assertEquals("none", row.penalty)
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    // ---------------------------------------------------------------------------------------
    // Last write wins
    // ---------------------------------------------------------------------------------------

    @Test
    fun lastWriteWins_doesNotTakeServerDataOnTheRowForTheLocalEdit() = runTest {
        insertSession()
        // The row was overwritten by server changes up to v3 (updated_at 11:00); the conflict's
        // snapshot is v2 (10:00). The row's updated_at is the server's, not the user's edit time,
        // so it must not make "local" look newer and re-send the user's older payload over v3.
        insertSolve(durationMs = 12_345L, version = 3L, penalty = "dnf", updatedAt = "2026-09-01T11:00:00.000Z")
        val snapshot = serverSolve(version = 2L, durationMs = 11_500L, updatedAt = "2026-09-01T10:00:00.000Z")
        val conflict = resolver.recordConflict(
            ownerId = owner,
            mutationId = "mut-lww",
            entityType = "solve",
            entityId = solveId,
            serverVersion = 2L,
            serverUpdatedAt = null,
            localPayloadJson = json.encodeToString(
                SolveSyncPayload.serializer(),
                SolveSyncPayload(id = solveId, sessionId = sessionId, durationMs = 10_000L, penalty = "plus_two", solvedAt = solvedAt, scramble = "R U R' U'", event = "3x3")
            ),
            serverPayloadJson = json.encodeToString(SolveSnapshotDto.serializer(), snapshot)
        )

        assertTrue(resolver.resolveConflict(conflict.conflictId, ConflictPolicy.LAST_WRITE_WINS))

        assertEquals(0, syncOutboxDao.countPending(owner))
        val row = solveDao.getSolveById(solveId)!!
        assertEquals(3L, row.version)
        assertEquals(12_345L, row.durationMs)
        assertTrue(conflictDao.getConflictById(conflict.conflictId)!!.resolved)
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun insertSession(name: String = "1 sep 2026 morning"): SessionEntity {
        val session = SessionEntity(
            id = sessionId,
            ownerId = owner,
            name = name,
            event = "3x3",
            kind = "automatic",
            startedAt = "2026-09-01T08:55:00.000Z",
            version = 1L,
            updatedAt = "2026-09-01T08:55:00.000Z"
        )
        sessionDao.insert(session)
        return session
    }

    private suspend fun insertSolve(
        durationMs: Long,
        version: Long = 1L,
        penalty: String = "none",
        timingDevice: String = "keyboard",
        updatedAt: String = solvedAt
    ): SolveEntity {
        val solve = SolveEntity(
            id = solveId,
            ownerId = owner,
            sessionId = sessionId,
            event = "3x3",
            durationMs = durationMs,
            penalty = penalty,
            solvedAt = solvedAt,
            scramble = "R U R' U'",
            version = version,
            updatedAt = updatedAt,
            timingDevice = timingDevice
        )
        solveDao.insert(solve)
        return solve
    }

    private fun serverSolve(version: Long, durationMs: Long, updatedAt: String, penalty: String = "none") = SolveSnapshotDto(
        id = solveId,
        sessionId = sessionId,
        durationMs = durationMs,
        penalty = penalty,
        solvedAt = solvedAt,
        scramble = "R U R' U'",
        event = "3x3",
        version = version,
        updatedAt = updatedAt,
        timingDevice = "keyboard"
    )

    private fun solveChange(dto: SolveSnapshotDto, cursor: Long) = ChangeDto(
        cursor = cursor,
        entity = "solve",
        entityId = dto.id,
        operation = "upsert",
        version = dto.version,
        data = json.encodeToJsonElement(SolveSnapshotDto.serializer(), dto),
        changedAt = dto.updatedAt
    )

    private fun conflictOutcome(mutationId: String, version: Long, current: JsonElement) = MutationOutcomeDto(
        mutationId = mutationId,
        status = "conflict",
        version = version,
        code = "version_conflict",
        message = "Version mismatch",
        current = current
    )

    private suspend fun recordSolveConflict(serverVersion: Long, localPayload: SolveSyncPayload) = resolver.recordConflict(
        ownerId = owner,
        mutationId = "mut-direct",
        entityType = "solve",
        entityId = solveId,
        serverVersion = serverVersion,
        serverUpdatedAt = null,
        localPayloadJson = json.encodeToString(SolveSyncPayload.serializer(), localPayload),
        serverPayloadJson = json.encodeToString(
            SolveSnapshotDto.serializer(),
            serverSolve(version = serverVersion, durationMs = 11_500L, updatedAt = "2026-09-01T10:00:00.000Z").copy(sessionId = null)
        )
    )

    // ---------------------------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------------------------

    private class FakeSyncApiClient : CubeSyncApiClient {
        val syncRequests = mutableListOf<SyncRequest>()
        var respond: suspend (SyncRequest) -> SyncResponse = { SyncResponse() }

        override suspend fun sync(request: SyncRequest, authToken: String?): SyncResponse {
            syncRequests += request
            return respond(request)
        }

        override suspend fun snapshot(request: SnapshotRequest, authToken: String?): SnapshotResponse =
            throw AssertionError("snapshot bootstrap not expected")

        override suspend fun register(request: com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest) = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
        override suspend fun verifyEmail(token: String) = throw NotImplementedError()
        override suspend fun login(request: com.maciekhetman.cubetimer.data.remote.dto.LoginRequest) = throw NotImplementedError()
        override suspend fun refreshToken(refreshToken: String) = throw NotImplementedError()
        override suspend fun logout(refreshToken: String) = Unit
        override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
        override suspend fun confirmPasswordReset(token: String, newPassword: String) = throw NotImplementedError()
        override suspend fun loginWithGoogle(request: com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest) = throw NotImplementedError()
        override suspend fun linkGoogle(request: com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest, authToken: String?) = Unit
        override suspend fun getCurrentUser(authToken: String?) = throw NotImplementedError()
        override suspend fun changePassword(request: com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest, authToken: String?) = Unit
        override suspend fun deleteAccount(authToken: String?) = Unit
    }

    private class FakeTokenStorage(private val userId: String) : TokenStorage {
        override val accessTokenFlow = MutableStateFlow<String?>("valid-token")
        override fun getAccessToken(): String? = "valid-token"
        override fun setAccessToken(token: String?) {}
        override fun getRefreshToken(): String? = "refresh-token"
        override fun setRefreshToken(token: String?) {}
        override fun getUserId(): String? = userId
        override fun getUserEmail(): String? = "cuber@example.com"
        override fun getUserRole(): String? = "user"
        override fun isUserEmailVerified(): Boolean = true
        override fun getDisplayName(): String? = "Cuber"
        override fun saveAuthSession(accessToken: String, refreshToken: String, userId: String, userEmail: String, userRole: String, emailVerified: Boolean, displayName: String?) {}
        override fun saveUser(user: User) {}
        override fun clearAuthData() {}
        override fun clearAll() {}
        override fun getCachedUser(): User? = null
        override fun getDeviceId(): String = "device-keep-local"
    }

    private class FakeAuthManager(initialState: AuthState) : AuthManager {
        override val authState: StateFlow<AuthState> = MutableStateFlow(initialState)
        override val currentUser: User? get() = (authState.value as? AuthState.Authenticated)?.user

        override suspend fun initialize() {}
        override suspend fun register(email: String, password: String) = throw NotImplementedError()
        override suspend fun login(email: String, password: String) = throw NotImplementedError()
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String) = throw NotImplementedError()
        override suspend fun verifyEmail(token: String) = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
        override suspend fun resetPassword(token: String, newPassword: String) = throw NotImplementedError()
        override suspend fun refreshSession() = throw NotImplementedError()
        override suspend fun logout() = throw NotImplementedError()
        override suspend fun adoptGuestData(userId: String) {}
    }
}
