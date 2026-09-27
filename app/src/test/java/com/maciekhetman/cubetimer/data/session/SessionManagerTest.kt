package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
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
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        database = CubeDatabase.createInMemory(context)
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
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
    fun testReactiveActiveSessionFlowFollowsAutomaticRollover() = runTest {
        sessionManager.getActiveSessionFlow(Mode.CUBE_3x3).test {
            // Initially null (no sessions created yet)
            assertNull(awaitItem())

            val t0 = LocalDateTime.of(2026, 8, 30, 18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val autoSession = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, t0)
            val emitted = awaitItem()
            assertNotNull(emitted)
            assertEquals(autoSession.id, emitted?.id)

            cancelAndIgnoreRemainingEvents()
        }

        // A solve, then a request past the inactivity gap: the next session becomes the active one.
        val t0 = LocalDateTime.of(2026, 8, 30, 18, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val first = sessionManager.getActiveSessionFlow(Mode.CUBE_3x3).first()!!
        database.solveDao().insert(
            SolveEntity(
                id = "solve-rollover",
                ownerId = "guest",
                sessionId = first.id,
                event = "3x3",
                durationMs = 11000L,
                solvedAt = Instant.ofEpochMilli(t0).toString()
            )
        )
        val next = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, t0 + 90 * 60 * 1000L)
        assertNotEquals(first.id, next.id)
        assertEquals(next.id, sessionManager.getActiveSessionFlow(Mode.CUBE_3x3).first()?.id)
    }

    @Test
    fun testManualSessionsFromSyncOrOldInstallsAreNeverActive() = runTest {
        // An open manual session (synced from another client, or created by an older build) plus
        // the per-mode selection an older build stored in DataStore.
        val manual = sessionRepository.createSession(
            Session(
                id = "manual-legacy",
                ownerId = "guest",
                name = "PB grind",
                event = Mode.CUBE_3x3,
                kind = SessionKind.MANUAL,
                startedAt = "2026-08-30T08:00:00.000Z"
            )
        )
        context.settingsDataStore.edit { prefs ->
            prefs[stringPreferencesKey("session_mode_guest_CUBE_3x3")] = "manual"
            prefs[stringPreferencesKey("active_manual_session_guest_CUBE_3x3")] = manual.id
        }

        assertNull(sessionManager.getActiveSessionFlow(Mode.CUBE_3x3).first())

        val active = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, System.currentTimeMillis())
        assertEquals(SessionKind.AUTOMATIC, active.kind)
        assertNotEquals(manual.id, active.id)
        assertEquals(active.id, sessionManager.getActiveSessionFlow(Mode.CUBE_3x3).first()?.id)
        // The manual session itself is left untouched (still open, not deleted).
        assertNull(sessionRepository.getSessionById(manual.id)?.endedAt)
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
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String) = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun verifyEmail(token: String) = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun resendVerificationEmail(email: String) = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String) = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String) = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun refreshSession() = AuthResult.Success(User("user-1", "user@test.com", "User", true))
        override suspend fun logout() = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) {}
    }
}
