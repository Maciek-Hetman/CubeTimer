package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.domain.session.AutomaticSessionHelper
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.maciekhetman.cubetimer.testutil.insertSession
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SessionConcurrencyStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var solvesRepository: SolvesRepository
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
        solvesRepository = SolvesRepository(
            context = context,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database
        )
        sessionManager = SessionManagerImpl(
            sessionRepository = sessionRepository,
            solveDao = database.solveDao()
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
    fun testSimultaneousSolveRecordingWhileOtherSessionsChurn() = runTest {
        val solverCount = 8
        val solvesPerCoroutine = 5

        coroutineScope {
            // Unrelated sessions (e.g. synced down from another client) being created and closed
            // concurrently must never steal solves from the automatic session.
            val churnJob = async {
                for (i in 1..5) {
                    val other = sessionRepository.insertSession("Synced $i", Mode.CUBE_3x3, "guest")
                    sessionRepository.closeSession(other.id, "guest")
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
                        assertEquals(SessionKind.AUTOMATIC, active.kind)

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

            churnJob.await()
            solverJobs.awaitAll()
        }

        // Verify total solves inserted in DB without data corruption
        val totalSolves = database.solveDao().getAllActiveSolvesForOwner("guest")
        assertEquals(solverCount * solvesPerCoroutine, totalSolves.size)

        // Every solve landed in the one automatic session (all were recorded within the gap).
        val sessionIds = totalSolves.map { it.sessionId }.toSet()
        assertEquals(1, sessionIds.size)
        val session = database.sessionDao().getSessionById(sessionIds.single()!!)
        assertNotNull("Solve sessionId must correspond to an existing session", session)
        assertEquals(SessionKind.AUTOMATIC.value, session!!.kind)
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
}
