package com.maciekhetman.cubetimer.data.local

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
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    // 2. SESSION FILTER SWITCHING ACROSS VARYING COUNTS & EDGE CASES
    // =========================================================================

    @Test
    fun testSessionFilterSwitchingAcrossActiveAllAndSpecificSessions() = runTest(testDispatcher) {
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
            context = context,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager,
            ioDispatcher = testDispatcher
        )

        // Create Sessions:
        // Session A: Active manual session with 10 solves
        val sessionA = sessionRepository.createManualSession("Session A (10)", Mode.CUBE_3x3, "guest")
        sessionManager.setActiveSession("guest", Mode.CUBE_3x3, sessionA.id)
        sessionManager.getActiveSessionFlow("guest", Mode.CUBE_3x3).first { it?.id == sessionA.id }

        // Session B: Empty session with 0 solves
        val sessionB = sessionRepository.createManualSession("Session B (0)", Mode.CUBE_3x3, "guest")

        // Session C: Large session with 120 solves (requires 3 pages of 50)
        val sessionC = sessionRepository.createManualSession("Session C (120)", Mode.CUBE_3x3, "guest")

        val baseTime = Instant.parse("2026-08-30T10:00:00.000Z")

        // Populate Session A: 10 solves
        val solvesA = (0 until 10).map { i ->
            SolveEntity(
                id = "solve-a-$i",
                ownerId = "guest",
                sessionId = sessionA.id,
                event = "3x3",
                durationMs = 12000L + i * 10,
                penalty = "none",
                solvedAt = baseTime.plus(i.toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U R' U'",
                version = 0L
            )
        }
        database.solveDao().insertAll(solvesA)

        // Populate Session C: 120 solves
        val solvesC = (0 until 120).map { i ->
            SolveEntity(
                id = "solve-c-$i",
                ownerId = "guest",
                sessionId = sessionC.id,
                event = "3x3",
                durationMs = 10000L + i * 10,
                penalty = "none",
                solvedAt = baseTime.plus((100 + i).toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U2 R' U'",
                version = 0L
            )
        }
        database.solveDao().insertAll(solvesC)

        // Populate a 2x2 mode solve to test event isolation: 15 solves
        val session2x2 = sessionRepository.createManualSession("Session 2x2 (15)", Mode.CUBE_2x2, "guest")
        val solves2x2 = (0 until 15).map { i ->
            SolveEntity(
                id = "solve-2x2-$i",
                ownerId = "guest",
                sessionId = session2x2.id,
                event = "2x2",
                durationMs = 4000L + i * 10,
                penalty = "none",
                solvedAt = baseTime.plus((300 + i).toLong(), ChronoUnit.MINUTES).toString(),
                scramble = "R U",
                version = 0L
            )
        }
        database.solveDao().insertAll(solves2x2)

        val viewModel = HistoryViewModel(
            application = context as android.app.Application,
            solvesRepository = solvesRepository,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            defaultDispatcher = testDispatcher
        )

        viewModel.uiState.first { state ->
            state.activeSession?.id == sessionA.id && !state.isLoading && state.totalCount == 10
        }
        advanceUntilIdle()

        // 1. Initial State: StatsFilter.ActiveSession (Session A, 10 solves)
        val stateActive = viewModel.uiState.value
        assertEquals(StatsFilter.ActiveSession, stateActive.currentFilter)
        assertEquals(10, stateActive.totalCount)
        assertEquals(10, stateActive.solves.size)
        assertFalse("10 items is less than PAGE_SIZE (50), hasMore must be false", stateActive.hasMore)
        assertFalse(stateActive.isLoading)
        assertEquals("solve-a-9", stateActive.solves.first().id) // newest first

        // 2. Switch to Empty Session (Session B, 0 solves)
        viewModel.setFilter(StatsFilter.SpecificSession(sessionB.id, sessionB.name))
        advanceUntilIdle()

        val stateEmpty = viewModel.uiState.value
        assertEquals(0, stateEmpty.totalCount)
        assertTrue(stateEmpty.solves.isEmpty())
        assertFalse(stateEmpty.hasMore)
        assertFalse(stateEmpty.isLoading)

        // 3. Switch to Large Session (Session C, 120 solves)
        viewModel.setFilter(StatsFilter.SpecificSession(sessionC.id, sessionC.name))
        advanceUntilIdle()

        val stateCPage1 = viewModel.uiState.value
        assertEquals(120, stateCPage1.totalCount)
        assertEquals(50, stateCPage1.solves.size)
        assertTrue("Has more pages to load", stateCPage1.hasMore)
        assertEquals("solve-c-119", stateCPage1.solves.first().id)

        // Load Page 2 (items 51-100)
        viewModel.loadMore()
        advanceUntilIdle()

        val stateCPage2 = viewModel.uiState.value
        assertEquals(100, stateCPage2.solves.size)
        assertTrue("Has page 3 left", stateCPage2.hasMore)

        // Load Page 3 (items 101-120)
        viewModel.loadMore()
        advanceUntilIdle()

        val stateCPage3 = viewModel.uiState.value
        assertEquals(120, stateCPage3.solves.size)
        assertFalse("All 120 items loaded, hasMore must be false", stateCPage3.hasMore)
        assertEquals("solve-c-0", stateCPage3.solves.last().id)

        // 4. Switch to AllSessions (Session A 10 + Session C 120 = 130 solves in 3x3; excludes 2x2 solves)
        viewModel.setFilter(StatsFilter.AllSessions)
        advanceUntilIdle()

        val stateAll = viewModel.uiState.value
        assertEquals(StatsFilter.AllSessions, stateAll.currentFilter)
        assertEquals(130, stateAll.totalCount) // 10 + 120 = 130, 2x2's 15 solves are excluded!
        assertEquals(50, stateAll.solves.size)
        assertTrue(stateAll.hasMore)

        // 5. Rapid switching stress test (ensure no crashes or race-condition corruptions)
        for (i in 0 until 5) {
            viewModel.setFilter(StatsFilter.ActiveSession)
            viewModel.setFilter(StatsFilter.SpecificSession(sessionC.id, sessionC.name))
            viewModel.setFilter(StatsFilter.SpecificSession(sessionB.id, sessionB.name))
            viewModel.setFilter(StatsFilter.AllSessions)
        }
        advanceUntilIdle()

        val stateAfterRapid = viewModel.uiState.value
        assertEquals(StatsFilter.AllSessions, stateAfterRapid.currentFilter)
        assertEquals(130, stateAfterRapid.totalCount)
        assertEquals(50, stateAfterRapid.solves.size)
        assertFalse(stateAfterRapid.isLoading)

        database.close()
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
            context = context,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager,
            ioDispatcher = testDispatcher
        )

        val session = sessionRepository.createManualSession("Default", Mode.CUBE_3x3, "guest")
        sessionManager.setActiveSession("guest", Mode.CUBE_3x3, session.id)
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
            defaultDispatcher = testDispatcher
        )

        viewModel.uiState.first { it.solves.isNotEmpty() && !it.isLoading }
        advanceUntilIdle()

        val solveItem = viewModel.uiState.value.solves.first()
        assertEquals(Penalty.NONE, solveItem.penalty)

        // Update penalty to DNF
        viewModel.updateSolvePenalty(solveItem, Penalty.DNF)
        advanceUntilIdle()

        assertEquals(Penalty.DNF, viewModel.uiState.value.solves.first().penalty)

        // Historical PB with DNF should NEVER be PB
        viewModel.selectSolveForDetail(viewModel.uiState.value.solves.first())
        advanceUntilIdle()

        val detail = viewModel.selectedSolveDetail.value
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
            context = context,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager,
            ioDispatcher = testDispatcher
        )

        val session = sessionRepository.createManualSession("Default", Mode.CUBE_3x3, "guest")
        sessionManager.setActiveSession("guest", Mode.CUBE_3x3, session.id)
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
            defaultDispatcher = testDispatcher
        )

        viewModel.uiState.first { it.solves.isNotEmpty() && !it.isLoading }
        advanceUntilIdle()

        val solveItem = viewModel.uiState.value.solves.first()
        viewModel.selectSolveForDetail(solveItem)
        advanceUntilIdle()

        val detail = viewModel.selectedSolveDetail.value
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
        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
