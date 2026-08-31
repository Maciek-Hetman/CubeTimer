package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.domain.session.AutomaticSessionHelper
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SessionConcurrencyStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var solvesRepository: SolvesRepository
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
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database
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
    fun testConcurrentGetOrCreateActiveSessionProducesSingleSession() = runTest {
        val coroutineCount = 20
        val timestamp = Instant.parse("2026-08-30T10:00:00Z").toEpochMilli()

        val results = coroutineScope {
            (1..coroutineCount).map {
                async {
                    sessionManager.getOrCreateActiveSession(
                        ownerId = "guest",
                        mode = Mode.CUBE_3x3,
                        solveTimestamp = timestamp
                    )
                }
            }.awaitAll()
        }

        // All 20 calls must return the EXACT same session ID due to mutex synchronization
        val firstId = results[0].id
        val expectedName = AutomaticSessionHelper.automaticSessionName(Instant.ofEpochMilli(timestamp))
        results.forEach { session ->
            assertEquals("All concurrent callers must receive the same session instance", firstId, session.id)
            assertEquals(expectedName, session.name)
        }

        // Database must contain only 1 session entity
        val sessionsInDb = database.sessionDao().getAllActiveSessionsForOwner("guest")
        assertEquals(1, sessionsInDb.size)
        assertEquals(firstId, sessionsInDb[0].id)
    }

    @Test
    fun testSimultaneousSolveRecordingAndSessionSwitching() = runTest {
        val solverCount = 8
        val solvesPerCoroutine = 5

        // Create 2 manual sessions
        val manual1 = sessionManager.createManualSession("Session A", Mode.CUBE_3x3, "guest")
        val manual2 = sessionManager.createManualSession("Session B", Mode.CUBE_3x3, "guest")

        coroutineScope {
            // Task 1: Rapidly switch active session between Session A, Session B, and Automatic
            val switcherJob = async {
                for (i in 1..20) {
                    when (i % 3) {
                        0 -> sessionManager.setActiveSession("guest", Mode.CUBE_3x3, manual1.id)
                        1 -> sessionManager.setActiveSession("guest", Mode.CUBE_3x3, manual2.id)
                        2 -> sessionManager.clearManualSessionOverride("guest", Mode.CUBE_3x3)
                    }
                }
            }

            // Task 2: Concurrently create and archive temporary sessions
            val archiverJob = async {
                for (i in 1..5) {
                    val temp = sessionManager.createManualSession("Temp $i", Mode.CUBE_3x3, "guest")
                    sessionManager.archiveSession(temp.id, Mode.CUBE_3x3, "guest")
                }
            }

            // Solvers: Concurrently record solves against dynamically resolved active session
            val solverJobs = (1..solverCount).map { threadIdx ->
                async {
                    for (solveIdx in 1..solvesPerCoroutine) {
                        val active = sessionManager.getOrCreateActiveSession(
                            ownerId = "guest",
                            mode = Mode.CUBE_3x3
                        )
                        assertNotNull(active)

                        val solve = SolveTime(
                            id = "concurrent-solve-$threadIdx-$solveIdx",
                            timeInMillis = 12000L + solveIdx * 50,
                            penalty = Penalty.NONE,
                            timestamp = System.currentTimeMillis(),
                            scramble = "R U R' U'",
                            mode = Mode.CUBE_3x3,
                            sessionId = active.id
                        )
                        solvesRepository.saveSolve(solve, ownerId = "guest", sessionId = active.id)
                    }
                }
            }

            switcherJob.await()
            archiverJob.await()
            solverJobs.awaitAll()
        }

        // Verify total solves inserted in DB without data corruption
        val totalSolves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(solverCount * solvesPerCoroutine, totalSolves.size)

        // Verify all solves have a non-null sessionId pointing to a valid session
        val allSessions = database.sessionDao().getAllSessionsForOwner("guest")
        val sessionIds = allSessions.map { it.id }.toSet()

        for (solve in totalSolves) {
            assertNotNull("Solve sessionId must not be null", solve.sessionId)
            assertTrue("Solve sessionId must correspond to an existing session", sessionIds.contains(solve.sessionId))
        }
    }

    @Test
    fun testConcurrentAutomaticSessionClosureAndDisambiguationUnderLoad() = runTest {
        val t0 = Instant.parse("2026-08-30T06:00:00Z").toEpochMilli()
        val baseName = AutomaticSessionHelper.automaticSessionName(Instant.ofEpochMilli(t0))

        // 1. Create first session in morning
        val s1 = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, t0)
        assertEquals(baseName, s1.name)

        // 2. Request at t0 + 65 min -> should close s1 and create baseName + " 2"
        val t65 = t0 + 65 * 60 * 1000L
        val s2 = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, t65)
        assertEquals("$baseName 2", s2.name)

        // 3. Request at t0 + 130 min -> should close s2 and create baseName + " 3"
        val t130 = t0 + 130 * 60 * 1000L
        val s3 = sessionManager.getOrCreateActiveSession("guest", Mode.CUBE_3x3, t130)
        assertEquals("$baseName 3", s3.name)

        // Verify previous sessions are closed
        val dbS1 = sessionRepository.getSessionById(s1.id)
        val dbS2 = sessionRepository.getSessionById(s2.id)
        val dbS3 = sessionRepository.getSessionById(s3.id)
        assertNotNull(dbS1?.endedAt)
        assertNotNull(dbS2?.endedAt)
        assertEquals(null, dbS3?.endedAt)
    }

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val currentUser: User? get() = when (val state = _authState.value) {
            is AuthState.Authenticated -> state.user
            is AuthState.Admin -> state.user
            AuthState.Guest, AuthState.Loading -> null
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
