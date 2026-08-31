package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.isOpen
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
class SessionDaoTest {

    private lateinit var database: CubeDatabase
    private lateinit var sessionDao: SessionDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        sessionDao = database.sessionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndGetActiveSession() = runTest {
        val session = SessionEntity(
            id = "sess-1",
            ownerId = "guest",
            name = "Morning 3x3",
            event = "3x3",
            kind = "automatic",
            startedAt = "2026-08-30T08:00:00.000Z"
        )
        sessionDao.insert(session)

        val active = sessionDao.getOpenAutomaticSession("guest", "3x3")
        assertNotNull(active)
        assertEquals("sess-1", active?.id)
        assertEquals("Morning 3x3", active?.name)
        assertTrue(active?.isOpen == true)
    }

    @Test
    fun testCloseSession() = runTest {
        val session = SessionEntity(
            id = "sess-close",
            ownerId = "guest",
            name = "Session To Close",
            event = "3x3",
            kind = "automatic",
            startedAt = "2026-08-30T08:00:00.000Z"
        )
        sessionDao.insert(session)

        sessionDao.closeSession("sess-close", "2026-08-30T09:00:00.000Z", "2026-08-30T09:00:00.000Z")

        val active = sessionDao.getOpenAutomaticSession("guest", "3x3")
        assertNull(active)

        val retrieved = sessionDao.getSessionById("sess-close")
        assertNotNull(retrieved)
        assertEquals("2026-08-30T09:00:00.000Z", retrieved?.endedAt)
        assertFalse(retrieved?.isOpen == true)
    }

    @Test
    fun testRenameAndArchiveSession() = runTest {
        val session = SessionEntity(
            id = "sess-rename",
            ownerId = "guest",
            name = "Original Name",
            event = "3x3",
            kind = "manual",
            startedAt = "2026-08-30T08:00:00.000Z"
        )
        sessionDao.insert(session)

        sessionDao.renameSession("sess-rename", "Updated Name", "2026-08-30T08:30:00.000Z")
        var retrieved = sessionDao.getSessionById("sess-rename")
        assertEquals("Updated Name", retrieved?.name)

        sessionDao.setArchived("sess-rename", true, "2026-08-30T09:00:00.000Z")
        retrieved = sessionDao.getSessionById("sess-rename")
        assertTrue(retrieved?.archived == true)

        val activeSessions = sessionDao.getAllActiveSessionsForOwner("guest")
        // Archived session is not filtered out from getAllActiveSessionsForOwner (which only filters deleted_at),
        // but observeActiveSessionsByEvent should filter it.
        sessionDao.observeActiveSessionsByEvent("guest", "3x3").test {
            val list = awaitItem()
            assertEquals(0, list.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testAdoptGuestSessions() = runTest {
        val session1 = SessionEntity(
            id = "s-1",
            ownerId = "guest",
            name = "Guest Session 1",
            event = "3x3",
            startedAt = "2026-08-30T08:00:00.000Z"
        )
        val session2 = SessionEntity(
            id = "s-2",
            ownerId = "guest",
            name = "Guest Session 2",
            event = "4x4",
            startedAt = "2026-08-30T09:00:00.000Z"
        )
        sessionDao.insertAll(listOf(session1, session2))

        val nowIso = "2026-08-30T10:00:00.000Z"
        sessionDao.adoptGuestSessions("guest", "user-abc", nowIso)

        val guestList = sessionDao.getAllActiveSessionsForOwner("guest")
        assertEquals(0, guestList.size)

        val userList = sessionDao.getAllActiveSessionsForOwner("user-abc")
        assertEquals(2, userList.size)
        assertTrue(userList.all { it.ownerId == "user-abc" && it.version == 0L })
    }
}
