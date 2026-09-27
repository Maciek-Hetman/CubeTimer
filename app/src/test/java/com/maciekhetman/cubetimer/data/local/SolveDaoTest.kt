package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SolveDaoTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        solveDao = database.solveDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndGetSolveById() = runTest {
        val solve = SolveEntity(
            id = UUID.randomUUID().toString(),
            ownerId = "guest",
            event = "3x3",
            durationMs = 12500L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U R' U'",
            version = 0L
        )

        solveDao.insert(solve)

        val retrieved = solveDao.getSolveById(solve.id)
        assertNotNull(retrieved)
        assertEquals(solve.id, retrieved?.id)
        assertEquals("guest", retrieved?.ownerId)
        assertEquals("3x3", retrieved?.event)
        assertEquals(12500L, retrieved?.durationMs)
        assertEquals("none", retrieved?.penalty)
        assertEquals("2026-08-30T10:00:00.000Z", retrieved?.solvedAt)
    }

    @Test
    fun testObserveSolvesByEvent() = runTest {
        val solve1 = SolveEntity(
            id = "solve-1",
            ownerId = "guest",
            event = "3x3",
            durationMs = 15000L,
            solvedAt = "2026-08-30T10:00:00.000Z"
        )
        val solve2 = SolveEntity(
            id = "solve-2",
            ownerId = "guest",
            event = "3x3",
            durationMs = 14000L,
            solvedAt = "2026-08-30T10:01:00.000Z"
        )
        val solve2x2 = SolveEntity(
            id = "solve-3",
            ownerId = "guest",
            event = "2x2",
            durationMs = 4000L,
            solvedAt = "2026-08-30T10:02:00.000Z"
        )

        solveDao.observeSolvesByEvent("guest", "3x3").test {
            assertEquals(0, awaitItem().size)

            solveDao.insert(solve1)
            val item1 = awaitItem()
            assertEquals(1, item1.size)
            assertEquals("solve-1", item1[0].id)

            solveDao.insert(solve2)
            val item2 = awaitItem()
            assertEquals(2, item2.size)
            assertEquals("solve-1", item2[0].id)
            assertEquals("solve-2", item2[1].id)

            // Insert 2x2 solve triggers table invalidation and emits filtered 3x3 solves
            solveDao.insert(solve2x2)
            val item3 = awaitItem()
            assertEquals(2, item3.size)
            assertEquals("solve-1", item3[0].id)
            assertEquals("solve-2", item3[1].id)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testSoftDelete() = runTest {
        val solve = SolveEntity(
            id = "solve-soft-del",
            ownerId = "guest",
            event = "3x3",
            durationMs = 10000L,
            solvedAt = "2026-08-30T10:00:00.000Z"
        )
        solveDao.insert(solve)

        val activeBefore = solveDao.getAllActiveSolvesForOwner("guest")
        assertEquals(1, activeBefore.size)

        solveDao.softDelete("solve-soft-del", "2026-08-30T10:05:00.000Z", "2026-08-30T10:05:00.000Z")

        val activeAfter = solveDao.getAllActiveSolvesForOwner("guest")
        assertEquals(0, activeAfter.size)

        // Raw query should still find the record with deleted_at populated
        val all = solveDao.getAllSolvesForOwner("guest")
        assertEquals(1, all.size)
        assertEquals("2026-08-30T10:05:00.000Z", all[0].deletedAt)
    }

    @Test
    fun testAdoptGuestSolves() = runTest {
        val solve1 = SolveEntity(
            id = "s1",
            ownerId = "guest",
            event = "3x3",
            durationMs = 11000L,
            solvedAt = "2026-08-30T10:00:00.000Z",
            version = 5L
        )
        val solve2 = SolveEntity(
            id = "s2",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            solvedAt = "2026-08-30T10:01:00.000Z",
            version = 3L
        )
        solveDao.insertAll(listOf(solve1, solve2))

        val nowIso = "2026-08-30T10:10:00.000Z"
        solveDao.adoptGuestSolves("guest", "user-123", nowIso)

        val guestSolves = solveDao.getAllActiveSolvesForOwner("guest")
        assertEquals(0, guestSolves.size)

        val userSolves = solveDao.getAllActiveSolvesForOwner("user-123")
        assertEquals(2, userSolves.size)
        assertTrue(userSolves.all { it.ownerId == "user-123" && it.version == 0L })
    }

    @Test
    fun testSessionForeignKeySetNullOnDelete() = runTest {
        val session = SessionEntity(
            id = "session-1",
            ownerId = "guest",
            name = "Session 1",
            event = "3x3",
            startedAt = "2026-08-30T10:00:00.000Z"
        )
        database.sessionDao().insert(session)

        val solve = SolveEntity(
            id = "solve-session-fk",
            ownerId = "guest",
            sessionId = "session-1",
            event = "3x3",
            durationMs = 12000L,
            solvedAt = "2026-08-30T10:01:00.000Z"
        )
        solveDao.insert(solve)

        val retrievedBefore = solveDao.getSolveById("solve-session-fk")
        assertEquals("session-1", retrievedBefore?.sessionId)

        // Delete session
        database.sessionDao().deleteById("session-1")

        val retrievedAfter = solveDao.getSolveById("solve-session-fk")
        assertNotNull(retrievedAfter)
        assertNull(retrievedAfter?.sessionId)
    }
}
