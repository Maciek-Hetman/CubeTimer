package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.dao.SyncMetadataDao
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
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

@RunWith(RobolectricTestRunner::class)
class SyncMetadataDaoTest {

    private lateinit var database: CubeDatabase
    private lateinit var syncMetadataDao: SyncMetadataDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        syncMetadataDao = database.syncMetadataDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testUpsertAndGetMetadata() = runTest {
        val metadata = SyncMetadataEntity(
            ownerId = "user-test",
            cursor = 10L,
            lastSyncTime = "2026-08-30T10:00:00.000Z",
            deviceId = "dev-123",
            deviceName = "Pixel 8",
            devicePlatform = "android"
        )
        syncMetadataDao.upsert(metadata)

        val retrieved = syncMetadataDao.getMetadata("user-test")
        assertNotNull(retrieved)
        assertEquals(10L, retrieved?.cursor)
        assertEquals("Pixel 8", retrieved?.deviceName)
        assertEquals("dev-123", retrieved?.deviceId)

        syncMetadataDao.updateCursor("user-test", 25L, "2026-08-30T10:15:00.000Z")
        val updated = syncMetadataDao.getMetadata("user-test")
        assertEquals(25L, updated?.cursor)
        assertEquals("2026-08-30T10:15:00.000Z", updated?.lastSyncTime)
    }

    @Test
    fun testSetSyncingAndError() = runTest {
        val metadata = SyncMetadataEntity(
            ownerId = "user-test",
            deviceId = "dev-123"
        )
        syncMetadataDao.upsert(metadata)

        syncMetadataDao.setSyncing("user-test", true)
        var current = syncMetadataDao.getMetadata("user-test")
        assertTrue(current?.isSyncing == true)

        syncMetadataDao.setSyncError("user-test", "Network timeout")
        current = syncMetadataDao.getMetadata("user-test")
        assertEquals(false, current?.isSyncing)
        assertEquals("Network timeout", current?.lastError)
    }
}
