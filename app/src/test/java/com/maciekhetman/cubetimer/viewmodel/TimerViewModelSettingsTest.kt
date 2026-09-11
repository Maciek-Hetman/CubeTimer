package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TimerViewModelSettingsTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fakeSessionManager: FakeTrackingSessionManager
    private lateinit var fakeAuthManager: FakeAuthManager
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
            database = database
        )
        settingsRepository = SettingsRepository(application)
        fakeAuthManager = FakeAuthManager()
        fakeSessionManager = FakeTrackingSessionManager()

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
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun testHideSessionMenuInTopBar_defaultsToFalse() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertFalse(timerViewModel.hideSessionMenuInTopBar.value)
    }

    @Test
    fun testSetHideSessionMenuInTopBar_updatesFlowAndResetsSessionModeAcrossModes() = runTest(testDispatcher) {
        advanceUntilIdle()

        timerViewModel.setHideSessionMenuInTopBar(true).join()
        advanceUntilIdle()
        for (i in 0..50) {
            if (timerViewModel.hideSessionMenuInTopBar.value) break
            kotlinx.coroutines.delay(20)
            advanceUntilIdle()
        }

        assertTrue(timerViewModel.hideSessionMenuInTopBar.value)
        // Verify that all modes were reset to automatic mode
        for (mode in Mode.entries) {
            assertTrue("Mode $mode should have cleared manual override", fakeSessionManager.clearedModes.contains(mode))
            assertTrue("Mode $mode should be set to automatic", fakeSessionManager.automaticModes[mode] == true)
        }

        // Toggling back to false
        timerViewModel.setHideSessionMenuInTopBar(false).join()
        advanceUntilIdle()
        for (i in 0..50) {
            if (!timerViewModel.hideSessionMenuInTopBar.value) break
            kotlinx.coroutines.delay(20)
            advanceUntilIdle()
        }
        assertFalse(timerViewModel.hideSessionMenuInTopBar.value)
    }

    @Test
    fun testScrambleScalePercent_flowReactivityAndMutation() = runTest(testDispatcher) {
        advanceUntilIdle()
        assertEquals(100, timerViewModel.scrambleScalePercent.value)

        timerViewModel.setScrambleScalePercent(85).join()
        advanceUntilIdle()
        for (i in 0..50) {
            if (timerViewModel.scrambleScalePercent.value == 85) break
            kotlinx.coroutines.delay(20)
            advanceUntilIdle()
        }
        assertEquals(85, timerViewModel.scrambleScalePercent.value)

        // Bounds coercion
        timerViewModel.setScrambleScalePercent(60).join()
        advanceUntilIdle()
        for (i in 0..50) {
            if (timerViewModel.scrambleScalePercent.value == 70) break
            kotlinx.coroutines.delay(20)
            advanceUntilIdle()
        }
        assertEquals(70, timerViewModel.scrambleScalePercent.value)

        timerViewModel.setScrambleScalePercent(150).join()
        advanceUntilIdle()
        for (i in 0..50) {
            if (timerViewModel.scrambleScalePercent.value == 140) break
            kotlinx.coroutines.delay(20)
            advanceUntilIdle()
        }
        assertEquals(140, timerViewModel.scrambleScalePercent.value)
    }

    private class FakeTrackingSessionManager : SessionManager {
        val clearedModes = mutableSetOf<Mode>()
        val automaticModes = mutableMapOf<Mode, Boolean>()
        private val _sessionMode = MutableStateFlow(SessionKind.MANUAL)

        override fun getActiveSessionFlow(mode: Mode): Flow<Session?> = MutableStateFlow(null)
        override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = MutableStateFlow(null)
        override fun getSessionModeFlow(mode: Mode): Flow<SessionKind> = _sessionMode.asStateFlow()
        override fun isAutomaticModeFlow(mode: Mode): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setSessionMode(mode: Mode, kind: SessionKind) {
            _sessionMode.value = kind
        }
        override suspend fun setAutomaticMode(mode: Mode, enabled: Boolean) {
            automaticModes[mode] = enabled
            if (enabled) _sessionMode.value = SessionKind.AUTOMATIC
        }
        override suspend fun setActiveSession(mode: Mode, sessionId: String) = Unit
        override suspend fun setActiveSession(ownerId: String, mode: Mode, sessionId: String) = Unit
        override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session {
            throw UnsupportedOperationException()
        }
        override suspend fun createManualSession(name: String, mode: Mode, ownerId: String?): Session {
            throw UnsupportedOperationException()
        }
        override suspend fun renameSession(id: String, newName: String, ownerId: String?): Session? = null
        override suspend fun archiveSession(id: String, mode: Mode?, ownerId: String?): Session? = null
        override suspend fun unarchiveSession(id: String, ownerId: String?): Session? = null
        override suspend fun deleteSession(id: String, mode: Mode?, ownerId: String?): Boolean = true
        override suspend fun clearManualSessionOverride(mode: Mode) {
            clearedModes.add(mode)
            _sessionMode.value = SessionKind.AUTOMATIC
        }
        override suspend fun clearManualSessionOverride(ownerId: String, mode: Mode) {
            clearedModes.add(mode)
            _sessionMode.value = SessionKind.AUTOMATIC
        }
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
