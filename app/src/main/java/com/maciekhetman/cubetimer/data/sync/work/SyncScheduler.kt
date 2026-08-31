package com.maciekhetman.cubetimer.data.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Interface controlling periodic and immediate background sync scheduling.
 */
interface SyncScheduler {
    fun schedulePeriodicSync()
    fun scheduleImmediateSync()
    fun cancelPeriodicSync()
    fun cancelAllSync()
}

/**
 * WorkManager-backed implementation of [SyncScheduler].
 */
class WorkManagerSyncScheduler(
    private val context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context)
) : SyncScheduler {

    override fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            FLEX_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag(TAG_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    override fun scheduleImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_SYNC)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            immediateRequest
        )
    }

    override fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
    }

    override fun cancelAllSync() {
        workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
        workManager.cancelUniqueWork(WORK_NAME_IMMEDIATE)
        workManager.cancelAllWorkByTag(TAG_SYNC)
    }

    companion object {
        const val WORK_NAME_PERIODIC = "cubetimer_periodic_sync"
        const val WORK_NAME_IMMEDIATE = "cubetimer_immediate_sync"
        const val TAG_SYNC = "sync_work"

        const val PERIODIC_INTERVAL_MINUTES = 15L
        const val FLEX_INTERVAL_MINUTES = 5L
    }
}
