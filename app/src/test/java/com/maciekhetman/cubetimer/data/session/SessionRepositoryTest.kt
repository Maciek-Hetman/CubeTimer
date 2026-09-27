package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
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
import com.maciekhetman.cubetimer.testutil.insertSession

@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var repository: SessionRepositoryImpl
    private var syncTriggerCount = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        syncTriggerCount = 0
        repository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            syncTrigger = { syncTriggerCount++ }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testCreateAndObserveSessionForGuest() = runTest {
        val nowIso = Instant.now().toString()
        val session = Session(
            id = "sess-guest-1",
            ownerId = "guest",
            name = "30 aug 2026 morning",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = nowIso
        )

        repository.observeActiveSessions("guest", Mode.CUBE_3x3).test {
            assertEquals(0, awaitItem().size)

            val created = repository.createSession(session)
            assertEquals("sess-guest-1", created.id)
            assertEquals("30 aug 2026 morning", created.name)

            val activeList = awaitItem()
            assertEquals(1, activeList.size)
            assertEquals("sess-guest-1", activeList[0].id)

            // Guest sessions must NOT enqueue outbox records
            val outboxMutations = database.syncOutboxDao().getPendingMutations("guest")
            assertEquals(0, outboxMutations.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testCreateSessionForAuthenticatedUserEnqueuesOutbox() = runTest {
        val nowIso = Instant.now().toString()
        val session = Session(
            id = "sess-user-1",
            ownerId = "user-abc",
            name = "Warmup 3x3",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = nowIso
        )

        val created = repository.createSession(session)
        assertEquals("sess-user-1", created.id)

        // Verify outbox record exists
        val pending = database.syncOutboxDao().getPendingMutations("user-abc")
        assertEquals(1, pending.size)
        val mutation = pending[0]
        assertEquals("user-abc", mutation.ownerId)
        assertEquals("session", mutation.entityType)
        assertEquals("sess-user-1", mutation.entityId)
        assertEquals("upsert", mutation.action)
        assertEquals(0L, mutation.baseVersion)
        assertNotNull(mutation.payloadJson)

        val payload = NetworkModule.json.decodeFromString<SessionSyncPayload>(mutation.payloadJson!!)
        assertEquals("sess-user-1", payload.id)
        assertEquals("Warmup 3x3", payload.name)
        assertEquals("3x3", payload.event)
        assertEquals("automatic", payload.kind)
        assertFalse(payload.archived)

        assertEquals(1, syncTriggerCount)
    }

    @Test
    fun testCloseSession() = runTest {
        val session = repository.createSession(
            Session(
                id = "sess-close-1",
                ownerId = "user-abc",
                name = "30 aug 2026 afternoon",
                event = Mode.CUBE_3x3,
                kind = SessionKind.AUTOMATIC,
                startedAt = Instant.now().toString(),
                endedAt = null
            )
        )
        assertNull(session.endedAt)

        val closed = repository.closeSession(session.id, "user-abc")
        assertNotNull(closed)
        assertNotNull(closed?.endedAt)

        val fetched = repository.getOpenAutomaticSession("user-abc", Mode.CUBE_3x3)
        assertNull(fetched)
    }

    @Test
    fun testDeleteSessionSoftDeletesAndEnqueuesDeleteMutation() = runTest {
        val session = repository.insertSession("Delete Me", Mode.CUBE_3x3, "user-abc")
        val success = repository.deleteSession(session.id, "user-abc")
        assertTrue(success)

        val fetched = repository.getSessionById(session.id)
        assertNotNull(fetched)
        assertTrue(fetched!!.isDeleted)
        assertNotNull(fetched.deletedAt)
        assertFalse(fetched.isOpen)

        val activeList = repository.getActiveSessions("user-abc", Mode.CUBE_3x3)
        assertTrue(activeList.none { it.id == session.id })

        val pending = database.syncOutboxDao().getPendingMutations("user-abc")
        val deleteMutation = pending.last()
        assertEquals("delete", deleteMutation.action)
        assertEquals("session", deleteMutation.entityType)
        assertEquals(session.id, deleteMutation.entityId)
        assertNull(deleteMutation.payloadJson)
    }

    @Test
    fun testGetSessionNamesWithPrefix() = runTest {
        repository.createSession(Session(name = "30 aug 2026 morning", event = Mode.CUBE_3x3, startedAt = Instant.now().toString()))
        repository.createSession(Session(name = "30 aug 2026 morning 2", event = Mode.CUBE_3x3, startedAt = Instant.now().toString()))
        repository.createSession(Session(name = "30 aug 2026 evening", event = Mode.CUBE_3x3, startedAt = Instant.now().toString()))

        val names = repository.getSessionNamesWithPrefix("guest", Mode.CUBE_3x3, "30 aug 2026 morning")
        assertEquals(2, names.size)
        assertTrue(names.contains("30 aug 2026 morning"))
        assertTrue(names.contains("30 aug 2026 morning 2"))
    }
}
