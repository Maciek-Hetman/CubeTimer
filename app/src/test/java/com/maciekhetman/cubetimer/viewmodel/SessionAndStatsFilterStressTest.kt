package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.AverageCalculator
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Adversarial stress and performance tests for Session switching and StatsFilter covering:
 * - Rapid session switching across 10+ sessions with reactive StateFlow updates
 * - Empty session filtering and safe statistical evaluation (no NaN, no IndexOutOfBounds)
 * - Massive volume datasets (5,000 solves) with multi-session filtering and batch deletion
 * - Rapid concurrent filter switching (150+ switches across ActiveSession, AllSessions, SpecificSession)
 * - Guest vs authenticated user session state isolation
 * - Statistical edge cases: Single-solve, +2 penalties, WCA DNF trimming, multi-DNF invalidation
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionAndStatsFilterStressTest {

    private lateinit var testDispatcher: TestDispatcher

    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fakeSessionManager: FakeMultiSessionManager
    private lateinit var fakeAuthManager: FakeStressAuthManager
    private lateinit var timerViewModel: TimerViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        runBlocking {
            application.settingsDataStore.edit { it.clear() }
            application.solvesDataStore.edit { it.clear() }
        }
        database = CubeDatabase.createInMemory(application)
        solvesRepository = SolvesRepository(
            context = application,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
        settingsRepository = SettingsRepository(application)
        fakeAuthManager = FakeStressAuthManager()
        fakeSessionManager = FakeMultiSessionManager()

        val defaultGuestSession = Session(
            id = "default_guest_session",
            ownerId = "guest",
            name = "Default Session",
            event = Mode.CUBE_3x3,
            kind = SessionKind.MANUAL,
            startedAt = "2026-08-30T00:00:00Z"
        )
        runBlocking {
            database.sessionDao().upsert(defaultGuestSession.toEntity())
        }
        fakeSessionManager.addSession(defaultGuestSession)

        timerViewModel = TimerViewModel(
            application = application,
            repository = solvesRepository,
            settingsRepository = settingsRepository,
            sessionManager = fakeSessionManager,
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            application.settingsDataStore.edit { it.clear() }
            application.solvesDataStore.edit { it.clear() }
        }
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------------
    // RAPID ACTIVE SESSION SWITCHING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `rapid active session switching correctly updates statsFilteredSolves instantaneously`() = runTest(testDispatcher) {
        val sessions = (0..9).map { idx ->
            val session = Session(
                id = "session_$idx",
                ownerId = "guest",
                name = "Session $idx",
                event = Mode.CUBE_3x3,
                kind = SessionKind.MANUAL,
                startedAt = "2026-08-30T10:00:00Z"
            )
            database.sessionDao().upsert(session.toEntity())
            fakeSessionManager.addSession(session)
            session
        }

        // Add 10 solves to each session (100 total solves)
        val all100Solves = mutableListOf<SolveTime>()
        val baseTime = 1000000L
        for (session in sessions) {
            for (solveIdx in 1..10) {
                val solve = SolveTime(
                    id = "solve_${session.id}_$solveIdx",
                    timeInMillis = 10000L + (solveIdx * 100L),
                    penalty = Penalty.NONE,
                    scramble = "R U R' U'",
                    mode = Mode.CUBE_3x3,
                    timestamp = baseTime + (solveIdx * 1000L),
                    sessionId = session.id
                )
                all100Solves.add(solve)
            }
        }
        timerViewModel.restoreSolves(all100Solves)
        advanceUntilIdle()

        assertEquals(100, timerViewModel.solves.value.size)

        // Rapidly switch active session 50 times
        for (i in 0..49) {
            val targetSession = sessions[i % sessions.size]
            fakeSessionManager.setActiveSession(Mode.CUBE_3x3, targetSession.id)
            advanceUntilIdle()

            val filtered = timerViewModel.statsFilteredSolves.value
            assertEquals(10, filtered.size)
            assertTrue(
                "All filtered solves must belong to active session ${targetSession.id}",
                filtered.all { it.sessionId == targetSession.id }
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // EMPTY SESSION STATS FILTERING & ZERO-SOLVE EDGE CASES
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `empty session produces empty stats without exceptions or NaN`() = runTest(testDispatcher) {
        val emptySession = Session(
            id = "empty_session_1",
            ownerId = "guest",
            name = "Empty Session",
            event = Mode.CUBE_3x3,
            kind = SessionKind.MANUAL,
            startedAt = "2026-08-30T10:00:00Z"
        )
        database.sessionDao().upsert(emptySession.toEntity())
        fakeSessionManager.addSession(emptySession)
        fakeSessionManager.setActiveSession(Mode.CUBE_3x3, emptySession.id)
        advanceUntilIdle()

        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()

        val filtered = timerViewModel.statsFilteredSolves.value
        assertTrue("Filtered solves for empty session must be empty", filtered.isEmpty())

        // Validate statistical functions on empty list
        assertNull("Ao5 on empty solves must be null", AverageCalculator.averageOfN(filtered, 5))
        assertNull("Ao12 on empty solves must be null", AverageCalculator.averageOfN(filtered, 12))
        assertNull("Ao50 on empty solves must be null", AverageCalculator.averageOfN(filtered, 50))
        assertNull("Ao100 on empty solves must be null", AverageCalculator.averageOfN(filtered, 100))
        assertNull("Best single on empty solves must be null", filtered.minOfOrNull { it.displayTime })
        assertEquals(0L, AverageCalculator.mean(filtered))
        assertEquals(0.0, AverageCalculator.standardDeviation(filtered), 0.001)
    }

    // ---------------------------------------------------------------------------------------------
    // MASSIVE VOLUME DATASET (5,000 SOLVES ACROSS SESSIONS)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `large scale 5000 solves filtering and session-scoped batch deletion`() = runTest(testDispatcher) {
        val sessionAlpha = Session(id = "ses_alpha", ownerId = "guest", name = "Alpha", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        val sessionBeta = Session(id = "ses_beta", ownerId = "guest", name = "Beta", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")

        database.sessionDao().upsert(sessionAlpha.toEntity())
        database.sessionDao().upsert(sessionBeta.toEntity())
        fakeSessionManager.addSession(sessionAlpha)
        fakeSessionManager.addSession(sessionBeta)
        fakeSessionManager.setActiveSession(Mode.CUBE_3x3, sessionAlpha.id)
        advanceUntilIdle()

        // Generate 3,000 solves in Alpha and 2,000 solves in Beta (5,000 total)
        val solvesAlpha = (1..3000).map { i ->
            SolveTime(
                id = "solve_alpha_$i",
                timeInMillis = 8000L + (i % 5000),
                penalty = Penalty.NONE,
                scramble = "F R U",
                mode = Mode.CUBE_3x3,
                timestamp = 10000000L + i,
                sessionId = sessionAlpha.id
            )
        }
        val solvesBeta = (1..2000).map { i ->
            SolveTime(
                id = "solve_beta_$i",
                timeInMillis = 9000L + (i % 5000),
                penalty = Penalty.NONE,
                scramble = "B L D",
                mode = Mode.CUBE_3x3,
                timestamp = 20000000L + i,
                sessionId = sessionBeta.id
            )
        }

        // Insert in bulk
        timerViewModel.restoreSolves(solvesAlpha + solvesBeta)
        advanceUntilIdle()

        // 1. Verify ActiveSession filter (Session Alpha active) -> 3,000 solves
        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()
        assertEquals(3000, timerViewModel.statsFilteredSolves.value.size)

        // 2. Verify AllSessions filter -> 5,000 solves
        timerViewModel.setStatsFilter(StatsFilter.AllSessions)
        advanceUntilIdle()
        assertEquals(5000, timerViewModel.statsFilteredSolves.value.size)

        // 3. Verify SpecificSession filter (Session Beta) -> 2,000 solves
        timerViewModel.setStatsFilter(StatsFilter.SpecificSession(sessionBeta.id, "Beta"))
        advanceUntilIdle()
        assertEquals(2000, timerViewModel.statsFilteredSolves.value.size)

        // 4. Batch delete filtered solves (deletes only Session Beta solves)
        timerViewModel.clearFilteredSolves()
        advanceUntilIdle()

        // After deleting Beta, AllSessions should have exactly 3,000 solves left (Alpha only)
        timerViewModel.setStatsFilter(StatsFilter.AllSessions)
        advanceUntilIdle()
        assertEquals(3000, timerViewModel.statsFilteredSolves.value.size)
        assertTrue(timerViewModel.statsFilteredSolves.value.all { it.sessionId == sessionAlpha.id })

        // SpecificSession Beta is now empty
        timerViewModel.setStatsFilter(StatsFilter.SpecificSession(sessionBeta.id, "Beta"))
        advanceUntilIdle()
        assertEquals(0, timerViewModel.statsFilteredSolves.value.size)
    }

    // ---------------------------------------------------------------------------------------------
    // RAPID CONCURRENT FILTER SWITCHING (150+ ITERATIONS)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `rapid concurrent filter switching maintains absolute state consistency`() = runTest(testDispatcher) {
        val s1 = Session(id = "s_rapid_1", ownerId = "guest", name = "S1", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        val s2 = Session(id = "s_rapid_2", ownerId = "guest", name = "S2", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        val s3 = Session(id = "s_rapid_3", ownerId = "guest", name = "S3", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        val sessionList = listOf(s1, s2, s3)

        sessionList.forEach {
            database.sessionDao().upsert(it.toEntity())
            fakeSessionManager.addSession(it)
        }

        // Insert 100 solves in each session (300 total)
        val solves = mutableListOf<SolveTime>()
        sessionList.forEachIndexed { sIdx, session ->
            for (i in 1..100) {
                solves.add(
                    SolveTime(
                        id = "solve_${session.id}_$i",
                        timeInMillis = 10000L + (sIdx * 1000L) + i,
                        penalty = Penalty.NONE,
                        scramble = "R U R' U'",
                        mode = Mode.CUBE_3x3,
                        timestamp = 100000L + (sIdx * 10000L) + i,
                        sessionId = session.id
                    )
                )
            }
        }
        timerViewModel.restoreSolves(solves)
        advanceUntilIdle()

        val filters = listOf(
            StatsFilter.ActiveSession,
            StatsFilter.AllSessions,
            StatsFilter.SpecificSession(s1.id, "S1"),
            StatsFilter.SpecificSession(s2.id, "S2"),
            StatsFilter.SpecificSession(s3.id, "S3")
        )

        for (step in 0 until 150) {
            val filter = filters[step % filters.size]
            val activeSessionTarget = sessionList[step % sessionList.size]

            fakeSessionManager.setActiveSession(Mode.CUBE_3x3, activeSessionTarget.id)
            timerViewModel.setStatsFilter(filter)
            advanceUntilIdle()

            val currentFiltered = timerViewModel.statsFilteredSolves.value
            when (filter) {
                is StatsFilter.ActiveSession -> {
                    assertEquals(100, currentFiltered.size)
                    assertTrue(currentFiltered.all { it.sessionId == activeSessionTarget.id })
                }
                is StatsFilter.AllSessions -> {
                    assertEquals(300, currentFiltered.size)
                }
                is StatsFilter.SpecificSession -> {
                    assertEquals(100, currentFiltered.size)
                    assertTrue(currentFiltered.all { it.sessionId == filter.sessionId })
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GUEST VS AUTHENTICATED USER SESSION STATE ISOLATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `guest vs authenticated user session state isolation`() = runTest(testDispatcher) {
        val guestSession = Session(id = "ses_guest", ownerId = "guest", name = "Guest Ses", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        database.sessionDao().upsert(guestSession.toEntity())
        fakeSessionManager.addSession(guestSession)
        fakeSessionManager.setActiveSession(Mode.CUBE_3x3, guestSession.id)
        advanceUntilIdle()

        // 1. Guest creates solves
        val guestSolve1 = SolveTime(id = "g_solve_1", timeInMillis = 15000L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 1000L, sessionId = guestSession.id)
        val guestSolve2 = SolveTime(id = "g_solve_2", timeInMillis = 16000L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 2000L, sessionId = guestSession.id)
        timerViewModel.addSolve(guestSolve1)
        timerViewModel.addSolve(guestSolve2)
        advanceUntilIdle()

        assertEquals(2, timerViewModel.solves.value.size)
        assertEquals(2, timerViewModel.statsFilteredSolves.value.size)

        // 2. Authenticate as User 1 (without adopting guest data)
        val user1 = User(id = "user_alpha_123", email = "alpha@cubetimer.io", displayName = "Alpha User")
        fakeAuthManager.setAuthState(AuthState.Authenticated(user1))
        advanceUntilIdle()

        // User 1 has 0 solves initially
        assertEquals(0, timerViewModel.solves.value.size)
        assertEquals(0, timerViewModel.statsFilteredSolves.value.size)

        // User 1 creates session and solve
        val userSession = Session(id = "ses_user1", ownerId = user1.id, name = "User 1 Ses", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        database.sessionDao().upsert(userSession.toEntity())
        fakeSessionManager.addSession(userSession)
        fakeSessionManager.setActiveSession(Mode.CUBE_3x3, userSession.id)
        advanceUntilIdle()

        val userSolve = SolveTime(id = "u_solve_1", timeInMillis = 8500L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 5000L, sessionId = userSession.id)
        timerViewModel.addSolve(userSolve)
        advanceUntilIdle()

        assertEquals(1, timerViewModel.solves.value.size)
        assertEquals(1, timerViewModel.statsFilteredSolves.value.size)
        assertEquals("u_solve_1", timerViewModel.solves.value.first().id)

        // 3. User 1 logs out -> Reverts to Guest
        fakeAuthManager.setAuthState(AuthState.Guest)
        advanceUntilIdle()

        assertEquals(2, timerViewModel.solves.value.size)
        assertTrue(timerViewModel.solves.value.none { it.id == "u_solve_1" })
        assertTrue(timerViewModel.solves.value.any { it.id == "g_solve_1" })
        assertTrue(timerViewModel.solves.value.any { it.id == "g_solve_2" })

        // 4. Authenticate as User 2 (separate user account)
        val user2 = User(id = "user_beta_456", email = "beta@cubetimer.io", displayName = "Beta User")
        fakeAuthManager.setAuthState(AuthState.Authenticated(user2))
        advanceUntilIdle()

        // User 2 sees 0 solves
        assertEquals(0, timerViewModel.solves.value.size)
        assertEquals(0, timerViewModel.statsFilteredSolves.value.size)
    }

    // ---------------------------------------------------------------------------------------------
    // STATISTICAL EDGE CASES: SINGLE SOLVE, +2 PENALTY, DNF
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `statistical edge cases single solve penalties and DNF`() = runTest(testDispatcher) {
        val edgeSession = Session(id = "ses_edge", ownerId = "guest", name = "Edge", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        database.sessionDao().upsert(edgeSession.toEntity())
        fakeSessionManager.addSession(edgeSession)
        fakeSessionManager.setActiveSession(Mode.CUBE_3x3, edgeSession.id)
        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()

        // Single clean solve: 11250ms
        val singleClean = SolveTime(id = "s_clean", timeInMillis = 11250L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 1000L, sessionId = edgeSession.id)
        timerViewModel.addSolve(singleClean)
        advanceUntilIdle()

        val list1 = timerViewModel.statsFilteredSolves.value
        assertEquals(1, list1.size)
        assertEquals(11250L, list1.first().displayTime)
        assertEquals(11250L, AverageCalculator.mean(list1))
        assertEquals(0.0, AverageCalculator.standardDeviation(list1), 0.001)
        assertNull(AverageCalculator.averageOfN(list1, 5))
        assertNull(AverageCalculator.averageOfN(list1, 12))

        // Update single solve to +2 penalty -> displayTime is 13250ms
        timerViewModel.updateSolvePenalty(singleClean, Penalty.PLUS_TWO)
        advanceUntilIdle()

        val list2 = timerViewModel.statsFilteredSolves.value
        assertEquals(1, list2.size)
        assertEquals(13250L, list2.first().displayTime)
        assertEquals(13250L, AverageCalculator.mean(list2))

        // Update single solve to DNF penalty -> 0 valid solves
        timerViewModel.updateSolvePenalty(singleClean, Penalty.DNF)
        advanceUntilIdle()

        val list3 = timerViewModel.statsFilteredSolves.value
        assertEquals(1, list3.size)
        val validSolves = list3.filter { it.penalty != Penalty.DNF }
        assertEquals(0, validSolves.size)
        assertEquals(0L, AverageCalculator.mean(list3))
        assertEquals(0.0, AverageCalculator.standardDeviation(list3), 0.001)
    }

    // ---------------------------------------------------------------------------------------------
    // STATISTICAL EDGE CASES: WCA DNF TRIMMING AND MULTI-DNF IN AVERAGES
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `statistical edge cases WCA DNF trimming and multi DNF invalidation`() = runTest(testDispatcher) {
        val dnfSession = Session(id = "ses_dnf", ownerId = "guest", name = "DNF", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL, startedAt = "2026-08-30T00:00:00Z")
        database.sessionDao().upsert(dnfSession.toEntity())
        fakeSessionManager.addSession(dnfSession)
        fakeSessionManager.setActiveSession(Mode.CUBE_3x3, dnfSession.id)
        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()

        // 5 solves with 1 DNF: [10000, 11000, 12000, 13000, DNF]
        // WCA averageOf5 trims 10000 (fastest) and DNF (slowest), mean of (11000 + 12000 + 13000)/3 = 12000L
        val solves5With1Dnf = listOf(
            SolveTime(id = "d1", timeInMillis = 10000L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 1L, sessionId = dnfSession.id),
            SolveTime(id = "d2", timeInMillis = 11000L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 2L, sessionId = dnfSession.id),
            SolveTime(id = "d3", timeInMillis = 12000L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 3L, sessionId = dnfSession.id),
            SolveTime(id = "d4", timeInMillis = 13000L, penalty = Penalty.NONE, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 4L, sessionId = dnfSession.id),
            SolveTime(id = "d5", timeInMillis = 14000L, penalty = Penalty.DNF, scramble = "U", mode = Mode.CUBE_3x3, timestamp = 5L, sessionId = dnfSession.id)
        )
        timerViewModel.restoreSolves(solves5With1Dnf)
        advanceUntilIdle()

        val filtered5 = timerViewModel.statsFilteredSolves.value
        assertEquals(5, filtered5.size)
        assertEquals(12000L, AverageCalculator.averageOfN(filtered5, 5))
        assertEquals(12000L, AverageCalculator.bestAverageOfN(filtered5, 5))

        // Update d4 to DNF -> 2 DNFs in 5 solves -> Ao5 must evaluate to null (DNF)
        timerViewModel.updateSolvePenalty(solves5With1Dnf[3], Penalty.DNF)
        advanceUntilIdle()

        val filteredWith2Dnf = timerViewModel.statsFilteredSolves.value
        assertNull("Ao5 with 2 DNFs must be null", AverageCalculator.averageOfN(filteredWith2Dnf, 5))

        // Add 7 more valid solves to reach 12 solves with 2 DNFs
        val extra7 = (6..12).map { i ->
            SolveTime(
                id = "d$i",
                timeInMillis = 10000L + (i * 200L),
                penalty = Penalty.NONE,
                scramble = "U",
                mode = Mode.CUBE_3x3,
                timestamp = i.toLong(),
                sessionId = dnfSession.id
            )
        }
        timerViewModel.restoreSolves(filteredWith2Dnf + extra7)
        advanceUntilIdle()

        val filtered12 = timerViewModel.statsFilteredSolves.value
        assertEquals(12, filtered12.size)
        // In 12 solves with 2 DNFs, AverageCalculator allows at most 1 DNF -> Ao12 must be null
        assertNull("Ao12 with 2 DNFs must be null", AverageCalculator.averageOfN(filtered12, 12))

        // Fix one DNF back to NONE -> now only 1 DNF out of 12 solves -> Ao12 must be valid Long
        timerViewModel.updateSolvePenalty(solves5With1Dnf[3], Penalty.NONE)
        advanceUntilIdle()

        val filtered12With1Dnf = timerViewModel.statsFilteredSolves.value
        assertNotNull("Ao12 with 1 DNF must be computed successfully", AverageCalculator.averageOfN(filtered12With1Dnf, 12))
    }

    // ---------------------------------------------------------------------------------------------
    // FAKE MULTI-SESSION MANAGER
    // ---------------------------------------------------------------------------------------------

    private class FakeMultiSessionManager : SessionManager {
        private val sessionsMap = mutableMapOf<String, Session>()
        private val _activeSession = MutableStateFlow<Session?>(null)
        private val _sessionMode = MutableStateFlow(SessionKind.MANUAL)

        fun addSession(session: Session) {
            sessionsMap[session.id] = session
            if (_activeSession.value == null) {
                _activeSession.value = session
            }
        }

        override fun getActiveSessionFlow(mode: Mode): Flow<Session?> = _activeSession.asStateFlow()
        override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = _activeSession.asStateFlow()
        override fun getSessionModeFlow(mode: Mode): Flow<SessionKind> = _sessionMode.asStateFlow()
        override fun isAutomaticModeFlow(mode: Mode): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setSessionMode(mode: Mode, kind: SessionKind) {
            _sessionMode.value = kind
        }
        override suspend fun setAutomaticMode(mode: Mode, enabled: Boolean) {
            _sessionMode.value = if (enabled) SessionKind.AUTOMATIC else SessionKind.MANUAL
        }
        override suspend fun setActiveSession(mode: Mode, sessionId: String) {
            _activeSession.value = sessionsMap[sessionId]
        }
        override suspend fun setActiveSession(ownerId: String, mode: Mode, sessionId: String) {
            setActiveSession(mode, sessionId)
        }
        override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session {
            return _activeSession.value ?: sessionsMap.values.first()
        }
        override suspend fun createManualSession(name: String, mode: Mode, ownerId: String?): Session {
            val s = Session(id = UUID.randomUUID().toString(), ownerId = ownerId ?: "guest", name = name, event = mode, kind = SessionKind.MANUAL, startedAt = "2026-08-30T10:00:00Z")
            addSession(s)
            return s
        }
        override suspend fun renameSession(id: String, newName: String, ownerId: String?): Session? {
            val s = sessionsMap[id]?.copy(name = newName)
            if (s != null) sessionsMap[id] = s
            return s
        }
        override suspend fun archiveSession(id: String, mode: Mode?, ownerId: String?): Session? = sessionsMap[id]
        override suspend fun unarchiveSession(id: String, ownerId: String?): Session? = sessionsMap[id]
        override suspend fun deleteSession(id: String, mode: Mode?, ownerId: String?): Boolean {
            sessionsMap.remove(id)
            return true
        }
        override suspend fun clearManualSessionOverride(mode: Mode) {
            _sessionMode.value = SessionKind.AUTOMATIC
        }
        override suspend fun clearManualSessionOverride(ownerId: String, mode: Mode) {
            _sessionMode.value = SessionKind.AUTOMATIC
        }
    }

    private class FakeStressAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val currentUser: User? get() = _authState.value.currentUser

        fun setAuthState(state: AuthState) {
            _authState.value = state
        }

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> {
            setAuthState(AuthState.Guest)
            return AuthResult.Success(Unit)
        }
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
