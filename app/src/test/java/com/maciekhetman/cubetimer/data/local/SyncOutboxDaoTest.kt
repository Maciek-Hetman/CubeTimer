package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncOutboxDaoTest {

    private lateinit var database: CubeDatabase
    private lateinit var syncOutboxDao: SyncOutboxDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        syncOutboxDao = database.syncOutboxDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testEnqueueAndGetPendingMutations() = runTest {
        val mutation1 = SyncOutboxEntity(
            id = "mut-1",
            ownerId = "user-1",
            entityType = "solve",
            entityId = "solve-100",
            action = "upsert",
            baseVersion = 0L,
            payloadJson = "{\"durationMs\": 12000}",
            clientTime = "2026-08-30T10:00:00.000Z",
            status = "pending"
        )
        val mutation2 = SyncOutboxEntity(
            id = "mut-2",
            ownerId = "user-1",
            entityType = "session",
            entityId = "sess-200",
            action = "upsert",
            baseVersion = 0L,
            payloadJson = "{\"name\": \"Morning\"}",
            clientTime = "2026-08-30T10:01:00.000Z",
            status = "pending"
        )
        syncOutboxDao.enqueueAll(listOf(mutation1, mutation2))

        val pending = syncOutboxDao.getPendingMutations("user-1", limit = 10)
        assertEquals(2, pending.size)
        assertEquals("mut-1", pending[0].id)
        assertEquals("mut-2", pending[1].id)
        assertEquals(2, syncOutboxDao.countPending("user-1"))
    }

    @Test
    fun testMarkInFlightAndMarkFailed() = runTest {
        val mutation = SyncOutboxEntity(
            id = "mut-flight",
            ownerId = "user-1",
            entityType = "solve",
            entityId = "solve-200",
            action = "upsert",
            clientTime = "2026-08-30T10:00:00.000Z",
            status = "pending"
        )
        syncOutboxDao.enqueue(mutation)

        syncOutboxDao.markInFlight(listOf("mut-flight"), attemptAt = 1725000000000L)

        // Should no longer appear in getPendingMutations (status != in_flight)
        val pending = syncOutboxDao.getPendingMutations("user-1")
        assertEquals(0, pending.size)

        // Mark failed
        syncOutboxDao.markFailed("mut-flight", "HTTP 500 Internal Error", attemptAt = 1725000001000L)
        val failed = syncOutboxDao.getMutationById("mut-flight")
        assertNotNull(failed)
        assertEquals("failed", failed?.status)
        assertEquals(1, failed?.attemptCount)
        assertEquals("HTTP 500 Internal Error", failed?.lastError)

        // Now that status is 'failed', getPendingMutations will return it again for retry
        val retryPending = syncOutboxDao.getPendingMutations("user-1")
        assertEquals(1, retryPending.size)
    }

    @Test
    fun testDeleteMutations() = runTest {
        val mut1 = SyncOutboxEntity(
            id = "m1",
            ownerId = "user-1",
            entityType = "solve",
            entityId = "s1",
            action = "upsert",
            clientTime = "2026-08-30T10:00:00.000Z"
        )
        val mut2 = SyncOutboxEntity(
            id = "m2",
            ownerId = "user-1",
            entityType = "solve",
            entityId = "s2",
            action = "upsert",
            clientTime = "2026-08-30T10:01:00.000Z"
        )
        syncOutboxDao.enqueueAll(listOf(mut1, mut2))

        syncOutboxDao.deleteMutations(listOf("m1"))
        assertEquals(1, syncOutboxDao.countPending("user-1"))
        assertNull(syncOutboxDao.getMutationById("m1"))
        assertNotNull(syncOutboxDao.getMutationById("m2"))

        syncOutboxDao.clearOutbox("user-1")
        assertEquals(0, syncOutboxDao.countPending("user-1"))
    }
}
