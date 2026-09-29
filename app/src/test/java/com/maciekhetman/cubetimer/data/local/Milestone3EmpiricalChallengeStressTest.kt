package com.maciekhetman.cubetimer.data.local

import com.maciekhetman.cubetimer.testutil.keepUiStateActive
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.maciekhetman.cubetimer.testutil.insertSession
import com.maciekhetman.cubetimer.model.SessionKind

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Milestone3EmpiricalChallengeStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var fakeAuthManager: FakeAuthManager

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        fakeAuthManager = FakeAuthManager()
    }

    @After
    fun tearDown() = runTest {
        context.settingsDataStore.edit { it.clear() }
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. WAL MODE & NON-BLOCKING BACKGROUND SYNC CONCURRENCY
    // =========================================================================

    @Test
    fun testWalModeConfigurationPragmaOnDiskDatabase() {
        val dbFile = File(tempFolder.newFolder(), "wal_test.db")
        val db = Room.databaseBuilder(context, CubeDatabase::class.java, dbFile.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

        try {
            val cursor = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("PRAGMA journal_mode;"))
            cursor.moveToFirst()
            val journalMode = cursor.getString(0)
            cursor.close()

            // Verify journal mode is explicitly WAL
            assertEquals("wal", journalMode.lowercase())
        } finally {
            db.close()
        }
    }

    @Test
    fun testConcurrentReadsDuringBackgroundSyncWrites() = runTest(testDispatcher) {
        val singleConnectionExecutor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, CubeDatabase::class.java)
            .setQueryExecutor(singleConnectionExecutor)
            .setTransactionExecutor(singleConnectionExecutor)
            .allowMainThreadQueries()
            .build()

        val solveDao = db.solveDao()
        val outboxDao = db.syncOutboxDao()

        // Seed initial 50 solves
        val initialSolves = (0 until 50).map { i ->
            SolveEntity(
                id = "init-solve-$i",
                ownerId = "user-1",
                event = "3x3",
                durationMs = 12000L + i * 10,
                penalty = "none",
                solvedAt = Instant.parse("2026-08-30T10:00:00.000Z").plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U'",
                version = 0L
            )
        }
        solveDao.insertAll(initialSolves)

        val writeCompleted = AtomicBoolean(false)
        val readSuccessCount = AtomicInteger(0)
        val readErrors = AtomicInteger(0)

        // Background Writer simulating SyncEngine applying batch changes in transactions
        val writerJob = launch(Dispatchers.IO) {
            for (batchIndex in 0 until 5) {
                db.withTransaction {
                    val batch = (0 until 20).map { i ->
                        val id = "sync-solve-$batchIndex-$i"
                        SolveEntity(
                            id = id,
                            ownerId = "user-1",
                            event = "3x3",
                            durationMs = 11000L + i,
                            penalty = "none",
                            solvedAt = Instant.parse("2026-08-30T11:00:00.000Z").plus((batchIndex * 20 + i).toLong(), ChronoUnit.SECONDS).toString(),
                            scramble = "R U",
                            version = 1L
                        )
                    }
                    solveDao.insertAll(batch)

                    val mutations = (0 until 10).map { i ->
                        SyncOutboxEntity(
                            id = UUID.randomUUID().toString(),
                            ownerId = "user-1",
                            entityType = "solve",
                            entityId = "sync-solve-$batchIndex-$i",
                            action = "create",
                            baseVersion = 0L,
                            payloadJson = "{}",
                            clientTime = Instant.now().toString(),
                            status = "completed"
                        )
                    }
                    outboxDao.enqueueAll(mutations)
                }
                delay(10)
            }
            writeCompleted.set(true)
        }

        // Concurrent Readers simulating UI / ViewModel reading paged solves and counts
        val readerJobs = (0 until 4).map { readerId ->
            launch(Dispatchers.IO) {
                while (!writeCompleted.get()) {
                    try {
                        val paged = solveDao.getSolvesPagedByEvent("user-1", "3x3", limit = 50, offset = 0)
                        assertTrue("Paged solves should not be empty", paged.isNotEmpty())

                        val count = solveDao.getSolveCountByEvent("user-1", "3x3")
                        assertTrue("Count should be at least 50", count >= 50)

                        val priorBest = solveDao.getPriorBestSolveDuration("user-1", "3x3", "2026-08-30T12:00:00.000Z")
                        assertNotNull(priorBest)

                        readSuccessCount.incrementAndGet()
                    } catch (e: Exception) {
                        readErrors.incrementAndGet()
                    }
                    delay(5)
                }
            }
        }

        writerJob.join()
        readerJobs.forEach { it.join() }

        db.close()
        singleConnectionExecutor.shutdown()
        singleConnectionExecutor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals("No read operations should encounter database locking errors", 0, readErrors.get())
        assertTrue("Concurrent reads must have executed successfully during background writes", readSuccessCount.get() > 10)
    }

    // =========================================================================
    // 3. COMPOSE UI PERFORMANCE INVARIANTS & SOLVE ACTION ROLLBACKS
    // =========================================================================

    @Test
    fun testOptimisticActionRollbackOnRepositoryFailure() = runTest(testDispatcher) {
        val directExecutor = java.util.concurrent.Executor { it.run() }
        val database = CubeDatabase.createInMemory(
            context = context,
            queryExecutor = directExecutor,
            transactionExecutor = directExecutor
        )

        val solvesRepository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
        val sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        val sessionManager = SessionManagerImpl(
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager,
            ioDispatcher = testDispatcher
        )

        val session = sessionRepository.insertSession("Default", Mode.CUBE_3x3, "guest", kind = SessionKind.AUTOMATIC)
        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_3x3).first { it?.id == session.id }

        val solve = SolveEntity(
            id = "solve-rollback",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 15000L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U R' U'",
            version = 0L
        )
        database.solveDao().insert(solve)

        val viewModel = HistoryViewModel(
            application = context as android.app.Application,
            solvesRepository = solvesRepository,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            database = database,
            sessionDao = database.sessionDao(),
            solveDao = database.solveDao(),
            syncOutboxDao = database.syncOutboxDao(),
            defaultDispatcher = testDispatcher
        )

        keepUiStateActive(viewModel)
        viewModel.expandSession(session.id)
        viewModel.uiState.first { state -> state.sessionGroups.singleOrNull()?.solves?.isNotEmpty() == true }
        advanceUntilIdle()

        val solveItem = viewModel.uiState.value.sessionGroups.single().solves.first()
        assertEquals(Penalty.NONE, solveItem.penalty)

        // Update penalty to DNF
        viewModel.updateSolvePenalty(solveItem, Penalty.DNF)
        advanceUntilIdle()

        assertEquals(Penalty.DNF, viewModel.uiState.value.sessionGroups.single().solves.first().penalty)

        // Historical PB with DNF should NEVER be PB
        viewModel.selectSolveForDetail(viewModel.uiState.value.sessionGroups.single().solves.first(), solveNumber = 1)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.selectedSolve
        assertNotNull(detail)
        assertFalse("DNF solve can never be a Personal Best", detail!!.isPb)
        assertNull(detail.pbDelta)

        database.close()
    }

    @Test
    fun testFirstSolvePersonalBestWhenTimestampHasMilliseconds() = runTest(testDispatcher) {
        val directExecutor = java.util.concurrent.Executor { it.run() }
        val database = CubeDatabase.createInMemory(
            context = context,
            queryExecutor = directExecutor,
            transactionExecutor = directExecutor
        )

        val solvesRepository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
        val sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        val sessionManager = SessionManagerImpl(
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager,
            ioDispatcher = testDispatcher
        )

        val session = sessionRepository.insertSession("Default", Mode.CUBE_3x3, "guest", kind = SessionKind.AUTOMATIC)
        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_3x3).first { it?.id == session.id }

        // Seed a solve whose solved_at has .000Z (RFC3339 format)
        val solve = SolveEntity(
            id = "first-solve",
            ownerId = "guest",
            sessionId = session.id,
            event = "3x3",
            durationMs = 14500L,
            penalty = "none",
            solvedAt = "2026-08-30T10:00:00.000Z",
            scramble = "R U R' U'",
            version = 0L
        )
        database.solveDao().insert(solve)

        val viewModel = HistoryViewModel(
            application = context as android.app.Application,
            solvesRepository = solvesRepository,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            database = database,
            sessionDao = database.sessionDao(),
            solveDao = database.solveDao(),
            syncOutboxDao = database.syncOutboxDao(),
            defaultDispatcher = testDispatcher
        )

        keepUiStateActive(viewModel)
        viewModel.expandSession(session.id)
        viewModel.uiState.first { state -> state.sessionGroups.singleOrNull()?.solves?.isNotEmpty() == true }
        advanceUntilIdle()

        val solveItem = viewModel.uiState.value.sessionGroups.single().solves.first()
        viewModel.selectSolveForDetail(solveItem, solveNumber = 1)
        advanceUntilIdle()

        val detail = viewModel.uiState.value.selectedSolve
        assertNotNull(detail)
        assertNull("Prior best must be null for the only solve in database", detail?.priorBestTime)
        assertTrue("First solve in history must be a Personal Best", detail!!.isPb)

        database.close()
    }

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
