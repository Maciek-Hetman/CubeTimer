package com.maciekhetman.cubetimer.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.dao.markAllFailedChunked
import com.maciekhetman.cubetimer.data.local.dao.markInFlightChunked
import com.maciekhetman.cubetimer.data.local.dao.resetInFlightChunked
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `dead` outbox rows (mutations the server permanently rejected) are kept for diagnostics but must
 * never be sent, counted as pending, or protect their entity from remote changes; and the bulk
 * status updates must stay under SQLite's pre-3.32 limit of 999 bind variables.
 */
@RunWith(RobolectricTestRunner::class)
class SyncOutboxDaoDeadLetterTest {

    private lateinit var database: CubeDatabase
    private lateinit var dao: SyncOutboxDao

    private val owner = "user-dead"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        dao = database.syncOutboxDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun mutation(id: String, entityId: String = id, time: Int = 0, status: String = "pending") = SyncOutboxEntity(
        id = id,
        ownerId = owner,
        entityType = "solve",
        entityId = entityId,
        action = "upsert",
        clientTime = "2026-08-30T10:%02d:00.000Z".format(time),
        status = status
    )

    @Test
    fun markDead_setsStatusErrorAndAttempt_andKeepsTheRow() = runTest {
        dao.enqueue(mutation("m1"))

        assertEquals(1, dao.markDead("m1", "HTTP 400 invalid_request: boom", attemptAt = 42L))

        val row = dao.getMutationById("m1")
        assertNotNull(row)
        assertEquals("dead", row!!.status)
        assertEquals(1, row.attemptCount)
        assertEquals(42L, row.lastAttemptAt)
        assertEquals("HTTP 400 invalid_request: boom", row.lastError)
    }

    @Test
    fun deadRows_areNotPending_notCounted_andNotObserved() = runTest {
        dao.enqueueAll(listOf(mutation("live", time = 1), mutation("doomed", time = 2), mutation("flying", time = 3, status = "in_flight")))
        dao.markDead("doomed", "rejected", attemptAt = 1L)

        assertEquals(listOf("live"), dao.getPendingMutations(owner).map { it.id })
        assertEquals("live + in_flight, not dead", 2, dao.countPending(owner))
        dao.observePendingCount(owner).test {
            assertEquals(2, awaitItem())
            dao.markDead("live", "rejected too", attemptAt = 2L)
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("the all-rows listing still shows dead rows", 3, dao.getAllPendingForOwner(owner).size)
    }

    @Test
    fun deadRows_doNotProtectTheirEntity_norCountAsANewerLocalEdit() = runTest {
        dao.enqueueAll(listOf(mutation("old", entityId = "solve-x", time = 1), mutation("dead-newest", entityId = "solve-x", time = 2)))
        assertEquals(2, dao.countPendingForEntity(owner, "solve", "solve-x"))
        assertEquals("dead-newest", dao.getPendingMutationForEntity(owner, "solve", "solve-x")?.id)

        dao.markDead("dead-newest", "rejected", attemptAt = 1L)

        assertEquals(1, dao.countPendingForEntity(owner, "solve", "solve-x"))
        assertEquals("old", dao.getPendingMutationForEntity(owner, "solve", "solve-x")?.id)

        dao.markDead("old", "rejected", attemptAt = 2L)

        assertEquals(0, dao.countPendingForEntity(owner, "solve", "solve-x"))
        assertNull(dao.getPendingMutationForEntity(owner, "solve", "solve-x"))
    }

    @Test
    fun markAllFailed_updatesEveryRowInOneStatement() = runTest {
        dao.enqueueAll((1..5).map { mutation("m$it", time = it, status = "in_flight") })

        assertEquals(3, dao.markAllFailed(listOf("m1", "m3", "m5"), "HTTP 503", attemptAt = 9L))

        for (id in listOf("m1", "m3", "m5")) {
            val row = dao.getMutationById(id)!!
            assertEquals("failed", row.status)
            assertEquals(1, row.attemptCount)
            assertEquals(9L, row.lastAttemptAt)
            assertEquals("HTTP 503", row.lastError)
        }
        assertEquals("untouched", "in_flight", dao.getMutationById("m2")!!.status)
    }

    @Test
    fun chunkedBulkUpdates_stayUnderTheOldSqliteVariableLimit() = runTest {
        val strictDao = OldSqliteOutboxDao(dao)
        val ids = (1..1_500).map { "bulk-$it" }
        dao.enqueueAll(ids.mapIndexed { i, id -> mutation(id, time = i % 60) })

        assertEquals(1_500, strictDao.markInFlightChunked(ids, attemptAt = 1L))
        assertTrue(dao.getPendingMutations(owner, limit = 10).isEmpty())

        assertEquals(1_500, strictDao.markAllFailedChunked(ids, "boom", attemptAt = 2L))
        assertEquals(1_500, dao.getPendingMutations(owner, limit = 5_000).size)
        assertTrue(dao.getAllPendingForOwner(owner, limit = 5_000).all { it.status == "failed" && it.attemptCount == 1 })

        assertEquals(1_500, strictDao.markInFlightChunked(ids, attemptAt = 3L))
        assertEquals(1_500, strictDao.resetInFlightChunked(ids))
        assertTrue(dao.getAllPendingForOwner(owner, limit = 5_000).all { it.status == "pending" })

        assertTrue("bind variables per call: ${strictDao.maxIdsPerCall}", strictDao.maxIdsPerCall in 1..999)
    }

    @Test
    fun theUnchunkedUpdateReallyWouldHitTheLimit() = runTest {
        // Guards the guard: the strict decorator must reject an oversized list, or the test above proves nothing.
        val strictDao = OldSqliteOutboxDao(dao)
        try {
            strictDao.markAllFailed((1..1_000).map { "x$it" }, "boom", attemptAt = 1L)
            throw AssertionError("oversized IN list should have been rejected")
        } catch (e: SQLiteException) {
            // expected
        }
    }

    /** Mimics SQLite < 3.32: a statement with more than 999 bind variables fails to compile. */
    private class OldSqliteOutboxDao(private val delegate: SyncOutboxDao) : SyncOutboxDao by delegate {
        var maxIdsPerCall = 0
            private set

        private fun check(ids: List<String>) {
            maxIdsPerCall = maxOf(maxIdsPerCall, ids.size)
            if (ids.size > 999) throw SQLiteException("too many SQL variables")
        }

        override suspend fun markInFlight(ids: List<String>, attemptAt: Long): Int {
            check(ids)
            return delegate.markInFlight(ids, attemptAt)
        }

        override suspend fun resetInFlight(ids: List<String>): Int {
            check(ids)
            return delegate.resetInFlight(ids)
        }

        override suspend fun markAllFailed(ids: List<String>, error: String?, attemptAt: Long): Int {
            check(ids)
            return delegate.markAllFailed(ids, error, attemptAt)
        }
    }
}
