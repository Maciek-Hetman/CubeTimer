package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.sync.work.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var fakeSyncEngine: FakeSyncEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fakeSyncEngine = FakeSyncEngine()
    }

    private fun createWorker(): SyncWorker {
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return SyncWorker(appContext, workerParameters, fakeSyncEngine)
                }
            })
            .build()
    }

    @Test
    fun doWork_whenSyncSucceeds_returnsSuccessResult() = runTest {
        fakeSyncEngine.resultToReturn = SyncResult.Success(mutationsSynced = 3, changesApplied = 5)
        val worker = createWorker()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_whenSyncIsNoOp_returnsSuccessResult() = runTest {
        fakeSyncEngine.resultToReturn = SyncResult.NoOp
        val worker = createWorker()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_whenSyncOffline_returnsRetryResult() = runTest {
        fakeSyncEngine.resultToReturn = SyncResult.Offline("No network")
        val worker = createWorker()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun doWork_whenSyncAuthError_returnsFailureResult() = runTest {
        fakeSyncEngine.resultToReturn = SyncResult.AuthError("Token invalid")
        val worker = createWorker()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun doWork_whenSyncGeneralError_returnsRetryResult() = runTest {
        fakeSyncEngine.resultToReturn = SyncResult.Error("Transient error")
        val worker = createWorker()

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    private class FakeSyncEngine : SyncEngine {
        var resultToReturn: SyncResult = SyncResult.Success()

        override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.SYNCED)
        override val lastSyncedAt: StateFlow<Long?> = MutableStateFlow(null)
        override val isSyncing: StateFlow<Boolean> = MutableStateFlow(false)
        override val stateManager: SyncStateManager = SyncStateManager()

        override fun observePendingMutationsCount(ownerId: String): Flow<Int> = emptyFlow()
        override fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>> = emptyFlow()

        override suspend fun sync(ownerId: String?): SyncResult = resultToReturn
        override suspend fun runSnapshotBootstrap(ownerId: String): Long = 0L
        override suspend fun resolveConflictKeepServer(conflictId: String): Boolean = true
        override suspend fun resolveConflictKeepLocal(conflictId: String): Boolean = true
    }
}
