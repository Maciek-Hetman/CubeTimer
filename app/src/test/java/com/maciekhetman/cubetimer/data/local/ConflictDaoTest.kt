package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.local.dao.ConflictDao
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConflictDaoTest {

    private lateinit var database: CubeDatabase
    private lateinit var conflictDao: ConflictDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        conflictDao = database.conflictDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndResolveConflict() = runTest {
        val conflict = ConflictEntity(
            conflictId = "conf-1",
            ownerId = "user-1",
            mutationId = "mut-1",
            entityType = "solve",
            entityId = "solve-100",
            serverVersion = 3L,
            errorMessage = "Version mismatch",
            createdAt = "2026-08-30T10:00:00.000Z",
            resolved = false
        )
        conflictDao.insert(conflict)

        val retrieved = conflictDao.getConflictById("conf-1")
        assertNotNull(retrieved)
        assertEquals("solve-100", retrieved?.entityId)
        assertEquals(false, retrieved?.resolved)

        val unresolved = conflictDao.getAll("user-1")
        assertEquals(1, unresolved.size)

        conflictDao.resolveConflict("conf-1", "2026-08-30T10:05:00.000Z")

        conflictDao.observeUnresolvedConflicts("user-1").test {
            val list = awaitItem()
            assertEquals(0, list.size)
            cancelAndIgnoreRemainingEvents()
        }

        val resolved = conflictDao.getConflictById("conf-1")
        assertTrue(resolved?.resolved == true)
        assertEquals("2026-08-30T10:05:00.000Z", resolved?.resolvedAt)
    }
}
