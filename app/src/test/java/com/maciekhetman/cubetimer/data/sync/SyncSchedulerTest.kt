package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.maciekhetman.cubetimer.data.sync.work.WorkManagerSyncScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncScheduler(context, workManager)
    }

    @Test
    fun schedulePeriodicSync_enqueuesUniquePeriodicWork() {
        scheduler.schedulePeriodicSync()

        val workInfos = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME_PERIODIC).get()
        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        val workInfo = workInfos[0]
        assertTrue(workInfo.tags.contains(WorkManagerSyncScheduler.TAG_SYNC))
        assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)
    }

    @Test
    fun scheduleImmediateSync_enqueuesUniqueOneTimeWork() {
        scheduler.scheduleImmediateSync()

        val workInfos = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME_IMMEDIATE).get()
        assertNotNull(workInfos)
        assertEquals(1, workInfos.size)
        val workInfo = workInfos[0]
        assertTrue(workInfo.tags.contains(WorkManagerSyncScheduler.TAG_SYNC))
        assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)
    }

    @Test
    fun cancelPeriodicSync_cancelsPeriodicWork() {
        scheduler.schedulePeriodicSync()
        scheduler.cancelPeriodicSync()

        val workInfos = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME_PERIODIC).get()
        assertNotNull(workInfos)
        assertTrue(workInfos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun cancelAllSync_cancelsAllSyncWork() {
        scheduler.schedulePeriodicSync()
        scheduler.scheduleImmediateSync()
        scheduler.cancelAllSync()

        val periodic = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME_PERIODIC).get()
        assertTrue(periodic.all { it.state == WorkInfo.State.CANCELLED })

        val immediate = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME_IMMEDIATE).get()
        assertTrue(immediate.all { it.state == WorkInfo.State.CANCELLED })
    }
}
