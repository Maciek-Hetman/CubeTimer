package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {

    private lateinit var testDispatcher: TestDispatcher

    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var sessionManager: SessionManagerImpl
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(application)
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
            context = application,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager
        )
        viewModel = SessionViewModel(
            application = application,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            application.settingsDataStore.edit { it.clear() }
        }
        Dispatchers.resetMain()
    }

    @Test
    fun testModeSwitching() = runTest(testDispatcher) {
        assertEquals(Mode.CUBE_3x3, viewModel.currentMode.value)
        viewModel.setMode(Mode.CUBE_2x2)
        assertEquals(Mode.CUBE_2x2, viewModel.currentMode.value)
    }

    @Test
    fun testCreateAndSwitchManualSession() = runTest(testDispatcher) {
        viewModel.createManualSession("My Practice").join()

        val active = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3)
        assertEquals("My Practice", active.name)
        assertEquals(SessionKind.MANUAL, active.kind)

        val list = sessionRepository.getActiveSessions("guest", Mode.CUBE_3x3)
        assertEquals(1, list.size)
        assertEquals("My Practice", list[0].name)
    }

    @Test
    fun testRenameSession() = runTest(testDispatcher) {
        viewModel.createManualSession("Old Practice").join()
        val active = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3)

        viewModel.renameSession(active.id, "Renamed Practice").join()
        val updated = sessionRepository.getSessionById(active.id)
        assertEquals("Renamed Practice", updated?.name)
    }

    @Test
    fun testArchiveAndUnarchiveSession() = runTest(testDispatcher) {
        viewModel.createManualSession("Session To Archive").join()
        val active = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3)

        // Archive session
        viewModel.archiveSession(active.id).join()
        val archivedList = sessionRepository.observeArchivedSessions("guest", Mode.CUBE_3x3).first()
        assertEquals(1, archivedList.size)
        assertEquals(active.id, archivedList[0].id)

        // Unarchive session
        viewModel.unarchiveSession(active.id).join()
        val unarchivedList = sessionRepository.observeArchivedSessions("guest", Mode.CUBE_3x3).first()
        assertEquals(0, unarchivedList.size)
        val activeList = sessionRepository.getActiveSessions("guest", Mode.CUBE_3x3)
        assertEquals(1, activeList.size)
    }

    @Test
    fun testDeleteSession() = runTest(testDispatcher) {
        viewModel.createManualSession("Session To Delete").join()
        val active = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3)

        viewModel.deleteSession(active.id).join()
        val activeList = sessionRepository.getActiveSessions("guest", Mode.CUBE_3x3)
        assertEquals(0, activeList.size)
    }

    @Test
    fun testSetAutomaticModeAndSwitchToAutomatic() = runTest(testDispatcher) {
        viewModel.createManualSession("Manual 1").join()
        sessionManager.setSessionMode(Mode.CUBE_3x3, SessionKind.MANUAL)
        assertFalse(sessionManager.isAutomaticModeFlow(Mode.CUBE_3x3).first())

        viewModel.switchToAutomaticSession().join()
        assertTrue(sessionManager.isAutomaticModeFlow(Mode.CUBE_3x3).first())
    }

    @Test
    fun testValidationEmptyNameReturnsError() = runTest(testDispatcher) {
        viewModel.createManualSession("   ").join()
        assertEquals("Session name cannot be empty", viewModel.errorMessage.value)
        viewModel.clearError()
        assertNull(viewModel.errorMessage.value)
    }

    private class FakeAuthManager(
        initialState: AuthState = AuthState.Guest
    ) : AuthManager {
        private val _authState = MutableStateFlow(initialState)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val currentUser: User? get() = _authState.value.currentUser

        fun setAuthState(state: AuthState) {
            _authState.value = state
        }

        override suspend fun initialize() {}
        override suspend fun register(email: String, password: String) = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String) = AuthResult.Success(User("user-1", email, "User", true))
        override suspend fun loginWithGoogle(idToken: String) = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun verifyEmail(token: String) = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun resendVerificationEmail(email: String) = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String) = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String) = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun refreshSession() = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun logout() = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) {}
    }
}
