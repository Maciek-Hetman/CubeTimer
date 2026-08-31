package com.maciekhetman.cubetimer.data.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maciekhetman.cubetimer.CubeTimerApplication
import com.maciekhetman.cubetimer.data.sync.SyncEngine
import com.maciekhetman.cubetimer.data.sync.SyncResult

/**
 * AndroidX CoroutineWorker executing background sync cycles via [SyncEngine].
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val injectedSyncEngine: SyncEngine? = null
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (isStopped) {
            return Result.retry()
        }

        val engine = injectedSyncEngine
            ?: (applicationContext as? CubeTimerApplication)?.syncEngine
            ?: CubeTimerApplication.getSyncEngineInstance(applicationContext)

        return try {
            val result = engine.sync()
            when (result) {
                is SyncResult.Success -> Result.success()
                is SyncResult.NoOp -> Result.success()
                is SyncResult.Offline -> {
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                is SyncResult.AuthError -> Result.failure()
                is SyncResult.Error -> {
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
