package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
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
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.User
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsFilterTest {

    private lateinit var testDispatcher: TestDispatcher

    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fakeSessionManager: FakeSessionManager
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var timerViewModel: TimerViewModel

    private val sessionAId = UUID.randomUUID().toString()
    private val sessionBId = UUID.randomUUID().toString()
    private lateinit var sessionA: Session
    private lateinit var sessionB: Session

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
            database = database
        )
        settingsRepository = SettingsRepository(application)
        fakeAuthManager = FakeAuthManager()

        val nowIso = "2026-08-30T10:00:00Z"
        sessionA = Session(
            id = sessionAId,
            ownerId = "guest",
            name = "Session Alpha",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = nowIso
        )
        sessionB = Session(
            id = sessionBId,
            ownerId = "guest",
            name = "Session Beta",
            event = Mode.CUBE_3x3,
            kind = SessionKind.AUTOMATIC,
            startedAt = nowIso
        )

        runBlocking {
            database.sessionDao().upsert(sessionA.toEntity())
            database.sessionDao().upsert(sessionB.toEntity())
        }

        fakeSessionManager = FakeSessionManager(sessionA, sessionB)

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
        try {
            if (::application.isInitialized) {
                runBlocking {
                    application.settingsDataStore.edit { it.clear() }
                    application.solvesDataStore.edit { it.clear() }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testDefaultStatsFilterIsAllSessions() = runTest(testDispatcher) {
        assertEquals(StatsFilter.AllSessions, timerViewModel.statsFilter.value)
    }

    @Test
    fun testStatsFilteredSolvesSeparatesSessions() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val solveA1 = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 12000L, mode = Mode.CUBE_3x3, timestamp = now, sessionId = sessionAId)
        val solveA2 = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 14000L, mode = Mode.CUBE_3x3, timestamp = now + 1000, sessionId = sessionAId)
        val solveB1 = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 9000L, mode = Mode.CUBE_3x3, timestamp = now + 2000, sessionId = sessionBId)

        timerViewModel.restoreSolves(listOf(solveA1, solveA2, solveB1))
        advanceUntilIdle()

        // Switch to ActiveSession filter (Session A active) -> should show 2 solves
        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()
        val activeFiltered = timerViewModel.statsFilteredSolves.value
        assertEquals(2, activeFiltered.size)
        assertTrue(activeFiltered.all { it.sessionId == sessionAId })

        // Switch filter to AllSessions -> should show all 3 solves
        timerViewModel.setStatsFilter(StatsFilter.AllSessions)
        advanceUntilIdle()
        val allFiltered = timerViewModel.statsFilteredSolves.value
        assertEquals(3, allFiltered.size)

        // Switch filter to SpecificSession B -> should show 1 solve
        timerViewModel.setStatsFilter(StatsFilter.SpecificSession(sessionBId, "Session Beta"))
        advanceUntilIdle()
        val specificFiltered = timerViewModel.statsFilteredSolves.value
        assertEquals(1, specificFiltered.size)
        assertEquals(solveB1.id, specificFiltered.first().id)
    }

    @Test
    fun testActiveSessionSwitchUpdatesStatsFilteredSolves() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val solveA = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 12000L, mode = Mode.CUBE_3x3, timestamp = now, sessionId = sessionAId)
        val solveB = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 9500L, mode = Mode.CUBE_3x3, timestamp = now + 1000, sessionId = sessionBId)

        timerViewModel.restoreSolves(listOf(solveA, solveB))
        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()

        assertEquals(1, timerViewModel.statsFilteredSolves.value.size)
        assertEquals(sessionAId, timerViewModel.statsFilteredSolves.value.first().sessionId)

        // The automatic session rolls over to Session B (e.g. after the inactivity gap)
        fakeSessionManager.switchActiveSession(sessionBId)
        advanceUntilIdle()

        assertEquals(1, timerViewModel.statsFilteredSolves.value.size)
        assertEquals(sessionBId, timerViewModel.statsFilteredSolves.value.first().sessionId)
    }

    @Test
    fun testActiveSessionFilterShowsNothingWithoutAnActiveSession() = runTest(testDispatcher) {
        val now = System.currentTimeMillis()
        val solveA = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 12000L, mode = Mode.CUBE_3x3, timestamp = now, sessionId = sessionAId)
        val solveB = SolveTime(id = UUID.randomUUID().toString(), timeInMillis = 9500L, mode = Mode.CUBE_3x3, timestamp = now + 1000, sessionId = sessionBId)

        timerViewModel.restoreSolves(listOf(solveA, solveB))
        timerViewModel.setStatsFilter(StatsFilter.ActiveSession)
        advanceUntilIdle()
        assertEquals(1, timerViewModel.statsFilteredSolves.value.size)

        // No open session any more (e.g. the active one was deleted): the filter must not fall back to every solve
        fakeSessionManager.clearActiveSession()
        advanceUntilIdle()

        assertEquals(2, timerViewModel.solves.value.size)
        assertTrue(timerViewModel.statsFilteredSolves.value.isEmpty())

        // Only the Active session filter is affected
        timerViewModel.setStatsFilter(StatsFilter.AllSessions)
        advanceUntilIdle()
        assertEquals(2, timerViewModel.statsFilteredSolves.value.size)
    }

    private class FakeSessionManager(
        private val sessionA: Session,
        private val sessionB: Session
    ) : SessionManager {
        private val _activeSession = MutableStateFlow<Session?>(sessionA)

        override fun getActiveSessionFlow(mode: Mode): Flow<Session?> = _activeSession.asStateFlow()
        override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = _activeSession.asStateFlow()
        override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session {
            return _activeSession.value ?: sessionA
        }

        /** Test control: simulate the automatic session rolling over to another session. */
        fun switchActiveSession(sessionId: String) {
            _activeSession.value = if (sessionId == sessionA.id) sessionA else sessionB
        }

        /** Test control: simulate there being no open session. */
        fun clearActiveSession() {
            _activeSession.value = null
        }
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
