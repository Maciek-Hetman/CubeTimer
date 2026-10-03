package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SolveDaoScopeAndDedupTest {

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
    fun testGetSolvesByScope_filtersCorrectly() = runTest {
        val sessionDao = database.sessionDao()
        sessionDao.insertAll(listOf(
            SessionEntity(id = "sess-a", ownerId = "u1", name = "Session A", event = "3x3", startedAt = "2026-09-11T09:00:00.000Z"),
            SessionEntity(id = "sess-b", ownerId = "u1", name = "Session B", event = "3x3", startedAt = "2026-09-11T09:01:00.000Z")
        ))

        val s1 = SolveEntity(id = "s1", ownerId = "u1", sessionId = "sess-a", event = "3x3", durationMs = 10000L, solvedAt = "2026-09-11T10:00:00.000Z")
        val s2 = SolveEntity(id = "s2", ownerId = "u1", sessionId = "sess-a", event = "3x3", durationMs = 11000L, solvedAt = "2026-09-11T10:01:00.000Z")
        val s3 = SolveEntity(id = "s3", ownerId = "u1", sessionId = "sess-b", event = "3x3", durationMs = 12000L, solvedAt = "2026-09-11T10:02:00.000Z")
        val s4 = SolveEntity(id = "s4", ownerId = "u1", sessionId = "sess-b", event = "4x4", durationMs = 40000L, solvedAt = "2026-09-11T10:03:00.000Z")
        val sDel = SolveEntity(id = "s-del", ownerId = "u1", sessionId = "sess-a", event = "3x3", durationMs = 9000L, solvedAt = "2026-09-11T10:04:00.000Z", deletedAt = "2026-09-11T10:05:00.000Z")
        solveDao.insertAll(listOf(s1, s2, s3, s4, sDel))

        // All active for owner
        val allOwner = solveDao.getSolvesByScope(ownerId = "u1")
        assertEquals(4, allOwner.size)

        // Scoped by sessionId
        val sessA = solveDao.getSolvesByScope(ownerId = "u1", sessionId = "sess-a")
        assertEquals(2, sessA.size)
        assertTrue(sessA.all { it.sessionId == "sess-a" })

        // Scoped by event
        val event3x3 = solveDao.getSolvesByScope(ownerId = "u1", event = "3x3")
        assertEquals(3, event3x3.size)
        assertTrue(event3x3.all { it.event == "3x3" })

        // Scoped by sessionId and event
        val sessB4x4 = solveDao.getSolvesByScope(ownerId = "u1", sessionId = "sess-b", event = "4x4")
        assertEquals(1, sessB4x4.size)
        assertEquals("s4", sessB4x4[0].id)
    }
}
