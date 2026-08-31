package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var sessionManager: SessionManagerImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
            context = context,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() = runTest {
        context.settingsDataStore.edit { it.clear() }
        database.close()
    }

    @Test
    fun testAutomaticSessionReuseWithin60Minutes() = runTest {
        val t0 = LocalDateTime.of(2026, 8, 30, 6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val session1 = sessionManager.getOrCreateActiveSession(
            ownerId = "guest",
            mode = Mode.CUBE_3x3,
            solveTimestamp = t0
        )

        assertNotNull(session1)
        assertEquals(SessionKind.AUTOMATIC, session1.kind)
        assertTrue(session1.name.contains("30 aug 2026 morning"))

        // Add a solve at t0 + 10 minutes (09:10 UTC)
        val t1 = t0 + 10 * 60 * 1000L
        val solve1 = SolveEntity(
            id = "solve-1",
            ownerId = "guest",
            sessionId = session1.id,
            event = "3x3",
            durationMs = 12000L,
            penalty = "none",
            solvedAt = Instant.ofEpochMilli(t1).toString()
        )
        database.solveDao().insert(solve1)

        // Request active session at t0 + 25 minutes (09:25 UTC) -> should reuse session1
        val t2 = t0 + 25 * 60 * 1000L
        val session2 = sessionManager.getOrCreateActiveSession(
            ownerId = "guest",
            mode = Mode.CUBE_3x3,
            solveTimestamp = t2
        )

        assertEquals(session1.id, session2.id)
    }

    @Test
    fun testAutomaticSessionExpirationAfter60Minutes() = runTest {
        val t0 = LocalDateTime.of(2026, 8, 30, 6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val session1 = sessionManager.getOrCreateActiveSession(
            ownerId = "guest",
            mode = Mode.CUBE_3x3,
            solveTimestamp = t0
        )

        // Solve at t0
        val solve1 = SolveEntity(
            id = "solve-1",
            ownerId = "guest",
            sessionId = session1.id,
            event = "3x3",
            durationMs = 10000L,
            penalty = "none",
            solvedAt = Instant.ofEpochMilli(t0).toString()
        )
        database.solveDao().insert(solve1)

        // Request session at t0 + 65 minutes (10:05 UTC) -> should close session1 and create session2
        val t65 = t0 + 65 * 60 * 1000L
        val session2 = sessionManager.getOrCreateActiveSession(
            ownerId = "guest",
            mode = Mode.CUBE_3x3,
            solveTimestamp = t65
        )

        assertNotEquals(session1.id, session2.id)
        assertEquals(SessionKind.AUTOMATIC, session2.kind)
        // Disambiguated with " 2" suffix since it's the same morning!
        assertEquals("30 aug 2026 morning 2", session2.name)

        // Verify session1 is closed in DB
        val closedSession1 = sessionRepository.getSessionById(session1.id)
        assertNotNull(closedSession1?.endedAt)
    }

    @Test
    fun testManualSessionModeSwitchAndFallback() = runTest {
        sessionManager.setSessionMode(Mode.CUBE_3x3, SessionKind.MANUAL)
        val modeKind = sessionManager.getSessionModeFlow(Mode.CUBE_3x3).first()
        assertEquals(SessionKind.MANUAL, modeKind)

        // Creating manual session
        val manual1 = sessionManager.createManualSession("Warmup", Mode.CUBE_3x3, "guest")
        assertEquals("Warmup", manual1.name)
        assertEquals(SessionKind.MANUAL, manual1.kind)

        // Active session should now be manual1
        val active = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3)
        assertEquals(manual1.id, active.id)

        // Create second manual session
        val manual2 = sessionManager.createManualSession("PB Grind", Mode.CUBE_3x3, "guest")
        val active2 = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3)
        assertEquals(manual2.id, active2.id)

        // Archive active manual2 -> should fallback
        sessionManager.archiveSession(manual2.id, Mode.CUBE_3x3, "guest")
        val isAuto = sessionManager.isAutomaticModeFlow(Mode.CUBE_3x3).first()
        assertTrue(isAuto)
    }

    @Test
    fun testReactiveActiveSessionFlow() = runTest {
        sessionManager.getActiveSessionFlow(Mode.CUBE_3x3).test {
            // Initially null (no active sessions created yet)
            assertNull(awaitItem())

            // Create automatic session
            val nowMs = System.currentTimeMillis()
            val autoSession = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, nowMs)
            val emitted = awaitItem()
            assertNotNull(emitted)
            assertEquals(autoSession.id, emitted?.id)

            // Switch to manual mode with new session
            val manualSession = sessionManager.createManualSession("Speed", Mode.CUBE_3x3, "guest")
            val emittedManual = awaitItem()
            assertNotNull(emittedManual)
            assertEquals(manualSession.id, emittedManual?.id)

            cancelAndIgnoreRemainingEvents()
        }
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
