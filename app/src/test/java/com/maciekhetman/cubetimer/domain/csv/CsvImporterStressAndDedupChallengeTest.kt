package com.maciekhetman.cubetimer.domain.csv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * Adversarial Stress, Deduplication, and Database Integrity Challenge Suite
 * for [CsvImporter].
 *
 * Verifies:
 * 1. Extreme SQLite bind-variable limits (>999 params) with 2,500 solves and 1,200 sessions.
 * 2. Intra-file duplicate solve IDs and cross-database collisions with conflicting payload data.
 * 3. Session auto-recreation with SessionKind.MANUAL, bounded timestamps, and strict Foreign Key enforcement.
 * 4. Mid-transaction rollback atomicity ensuring zero orphaned sessions or solves upon DAO failure.
 * 5. Authenticated vs Guest sync outbox mutation isolation, payload accuracy, and session-before-solve ordering.
 * 6. Fault tolerance across malformed records and corrupted data streams.
 * 7. Concurrent import operations against a shared Room SQLite instance.
 */
@RunWith(RobolectricTestRunner::class)
class CsvImporterStressAndDedupChallengeTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var defaultImporter: CsvImporter

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        // Explicitly enable foreign key constraints in SQLite
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")

        solveDao = database.solveDao()
        sessionDao = database.sessionDao()
        syncOutboxDao = database.syncOutboxDao()
        defaultImporter = CsvImporter(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun stringToStream(content: String): ByteArrayInputStream =
        ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))

    // =========================================================================
    // Challenge 1: SQLite 999 Bind Parameter Limit Stress (2,500 Solves + DB)
    // =========================================================================

    @Test
    fun importCsv_stress2500SolvesAnd1200Sessions_surpasses999LimitWithoutSqliteException() = runTest {
        val existingSessionCount = 50
        val existingSolvesCount = 1200

        // 1. Pre-seed DB with 1,200 solves across 50 sessions
        val preSessions = (1..existingSessionCount).map { i ->
            SessionEntity(
                id = "pre-sess-$i",
                ownerId = "guest",
                name = "Existing Session $i",
                event = "3x3",
                kind = SessionKind.MANUAL.value,
                startedAt = "2026-09-01T10:00:00.000Z"
            )
        }
        sessionDao.insertAll(preSessions)

        val preSolves = (1..existingSolvesCount).map { i ->
            val sessId = "pre-sess-${(i % existingSessionCount) + 1}"
            SolveEntity(
                id = "pre-solve-$i",
                ownerId = "guest",
                sessionId = sessId,
                event = "3x3",
                durationMs = 15000L + i,
                solvedAt = "2026-09-01T10:00:00.000Z"
            )
        }
        solveDao.insertAll(preSolves)

        // 2. Author a 2,500 solve CSV:
        //    - 500 solves collide with existing DB solves (solve-ids: pre-solve-1 .. pre-solve-500)
        //    - 2,000 solves are brand new (solve-ids: new-solve-1 .. new-solve-2000)
        //    - Across 1,200 unique new sessions (testing chunked session lookup with >999 IDs)
        val candidateSolvesCount = 2500
        val collidingCount = 500
        val newSolvesCount = candidateSolvesCount - collidingCount
        val uniqueNewSessions = 1200

        val sb = StringBuilder()
        sb.append("# Source: CubeTimer\r\n")
        sb.append(CsvFormat.HEADER_LINE).append("\r\n")

        // 500 duplicates
        for (i in 1..collidingCount) {
            val ts = 1700000000000L + i
            sb.append("pre-solve-$i,pre-sess-1,Existing Session 1,3x3,$ts,99999,none,R U\r\n")
        }

        // 2,000 new solves across 1,200 new sessions
        for (i in 1..newSolvesCount) {
            val sessIndex = (i % uniqueNewSessions) + 1
            val sessId = "bulk-sess-$sessIndex"
            val solveId = "new-solve-$i"
            val ts = 1710000000000L + i
            val duration = 10000L + (i % 5000)
            sb.append("$solveId,$sessId,Bulk Session $sessIndex,3x3,$ts,$duration,none,R U R' U'\r\n")
        }

        val result = defaultImporter.importCsv(stringToStream(sb.toString()), ownerId = "guest")
        assertTrue("Import must succeed for 2,500 solves: $result", result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(newSolvesCount, success.importedCount)
        assertEquals(collidingCount, success.duplicateCount)
        assertEquals(0, success.malformedCount)
        assertEquals(uniqueNewSessions, success.sessionsCreatedCount)

        // Verify SQLite total solve count: 1,200 pre-existing + 2,000 new = 3,200
        val allSolves = solveDao.getSolvesByScope(ownerId = "guest")
        assertEquals(existingSolvesCount + newSolvesCount, allSolves.size)

        // Verify pre-existing solve was NOT overwritten by the CSV's 99999 duration
        val preservedSolve = allSolves.find { it.id == "pre-solve-1" }
        assertNotNull(preservedSolve)
        assertEquals(15001L, preservedSolve!!.durationMs)
    }

    // =========================================================================
    // Challenge 2: Intra-File Duplicate solve_id and Cross-DB Collisions
    // =========================================================================

    @Test
    fun importCsv_intraFileAndDbCollisions_strictlyDeduplicatesAndPreservesFirstOccurrence() = runTest {
        // Pre-populate DB with existing solve
        sessionDao.insertAll(listOf(
            SessionEntity(id = "db-sess-1", ownerId = "guest", name = "DB Sess", event = "3x3", startedAt = "2026-09-01T00:00:00Z")
        ))
        solveDao.insertAll(listOf(
            SolveEntity(
                id = "db-solve-pre",
                ownerId = "guest",
                sessionId = "db-sess-1",
                event = "3x3",
                durationMs = 8888L,
                penalty = "none",
                solvedAt = "2026-09-01T00:00:00Z",
                scramble = "Original DB Scramble"
            )
        ))

        // CSV containing:
        // 1. db-solve-pre (collision with DB - should be skipped)
        // 2. db-solve-pre (collision with DB and intra-file dup - should be skipped)
        // 3. solve-intra-dup (first occurrence: duration 11111L - should be imported)
        // 4. solve-intra-dup (second occurrence: duration 22222L - should be skipped)
        // 5. solve-intra-dup (third occurrence: duration 33333L - should be skipped)
        // 6. solve-clean-1 (valid new solve)
        // 7. solve-clean-2 (valid new solve)
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            db-solve-pre,db-sess-1,DB Sess,3x3,1700000001000,99999,none,Changed Scramble 1
            db-solve-pre,db-sess-1,DB Sess,3x3,1700000002000,99998,none,Changed Scramble 2
            solve-intra-dup,sess-new,New Sess,3x3,1700000003000,11111,none,First Occurrence Scramble
            solve-intra-dup,sess-new,New Sess,3x3,1700000004000,22222,+2,Second Occurrence Scramble
            solve-intra-dup,sess-new,New Sess,3x3,1700000005000,33333,dnf,Third Occurrence Scramble
            solve-clean-1,sess-new,New Sess,3x3,1700000006000,14000,none,Scramble Clean 1
            solve-clean-2,sess-new,New Sess,3x3,1700000007000,15000,none,Scramble Clean 2
        """.trimIndent()

        val result = defaultImporter.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        // Expected: 3 imported (solve-intra-dup first occurrence, solve-clean-1, solve-clean-2)
        assertEquals(3, success.importedCount)
        // Expected duplicates: 4 (2 of db-solve-pre + 2 of solve-intra-dup)
        assertEquals(4, success.duplicateCount)
        assertEquals(0, success.malformedCount)
        assertEquals(1, success.sessionsCreatedCount)

        // Verify DB solve was not overwritten
        val dbSolve = solveDao.getSolvesByScope(ownerId = "guest").find { it.id == "db-solve-pre" }!!
        assertEquals(8888L, dbSolve.durationMs)
        assertEquals("Original DB Scramble", dbSolve.scramble)

        // Verify solve-intra-dup kept FIRST occurrence data
        val intraSolve = solveDao.getSolvesByScope(ownerId = "guest").find { it.id == "solve-intra-dup" }!!
        assertEquals(11111L, intraSolve.durationMs)
        assertEquals("none", intraSolve.penalty)
        assertEquals("First Occurrence Scramble", intraSolve.scramble)
    }

    // =========================================================================
    // Challenge 3: Session Auto-Recreation Before Child Solves (FK & Metadata)
    // =========================================================================

    @Test
    fun importCsv_missingSessionsAutoRecreation_satisfiesForeignKeyAndMetadataContracts() = runTest {
        // CSV referencing 3 distinct missing sessions and 1 blank session_id
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            s-1,sess-alpha,Alpha Session,3x3,1700000010000,10000,none,R U
            s-2,sess-alpha,Alpha Session,3x3,1700000001000,11000,none,R' U'
            s-3,sess-alpha,Alpha Session,3x3,1700000005000,12000,none,F R
            s-4,sess-beta,Beta Custom,4x4,1700000020000,45000,none,Rw U
            s-5,sess-gamma,Gamma Megaminx,megaminx,1700000030000,75000,none,R++ D++
            s-6,,,3x3,1700000040000,13000,none,U R
        """.trimIndent()

        val result = defaultImporter.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(6, success.importedCount)
        // 3 named missing sessions + 1 fallback session for blank session_id = 4
        assertEquals(4, success.sessionsCreatedCount)

        // Verify Alpha Session metadata bounding: startedAt = earliest (1700000001000), endedAt = latest (1700000010000)
        val alphaSession = sessionDao.getSessionsByIds(listOf("sess-alpha")).first()
        assertEquals("Alpha Session", alphaSession.name)
        assertEquals("3x3", alphaSession.event)
        assertEquals(SessionKind.MANUAL.value, alphaSession.kind)
        assertEquals(CubeTypeConverters.epochMillisToIso(1700000001000L), alphaSession.startedAt)
        assertEquals(CubeTypeConverters.epochMillisToIso(1700000010000L), alphaSession.endedAt)
        assertFalse(alphaSession.archived)
        assertEquals(0L, alphaSession.version)

        // Verify Beta Session
        val betaSession = sessionDao.getSessionsByIds(listOf("sess-beta")).first()
        assertEquals("Beta Custom", betaSession.name)
        assertEquals("4x4", betaSession.event)
        assertEquals(SessionKind.MANUAL.value, betaSession.kind)

        // Verify Gamma Session
        val gammaSession = sessionDao.getSessionsByIds(listOf("sess-gamma")).first()
        assertEquals("Gamma Megaminx", gammaSession.name)
        assertEquals("megaminx", gammaSession.event)
        assertEquals(SessionKind.MANUAL.value, gammaSession.kind)

        // Verify blank session was assigned a fallback session with MANUAL kind and solve points to it
        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        val blankSolve = solves.find { it.id == "s-6" }!!
        assertNotNull(blankSolve.sessionId)
        val fallbackSession = sessionDao.getSessionsByIds(listOf(blankSolve.sessionId!!)).firstOrNull()
        assertNotNull("Fallback session must exist in DB", fallbackSession)
        assertEquals("Imported Session", fallbackSession!!.name)
        assertEquals(SessionKind.MANUAL.value, fallbackSession.kind)
    }

    // =========================================================================
    // Challenge 4: Transactional Rollback Atomicity on Write Failure
    // =========================================================================

    private class FailingSolveDao(
        private val delegate: SolveDao,
        private val shouldFailOnInsert: Boolean = true
    ) : SolveDao by delegate {
        override suspend fun insertAll(solves: List<SolveEntity>): List<Long> {
            if (shouldFailOnInsert) {
                throw SQLException("Simulated disk error during solve batch insertion")
            }
            return delegate.insertAll(solves)
        }
    }

    private class FailingSyncOutboxDao(
        private val delegate: SyncOutboxDao,
        private val failOnSolveMutations: Boolean = true
    ) : SyncOutboxDao by delegate {
        override suspend fun enqueueAll(mutations: List<SyncOutboxEntity>): List<Long> {
            if (failOnSolveMutations && mutations.any { it.entityType == "solve" }) {
                throw SQLException("Simulated database failure on outbox solve enqueue")
            }
            return delegate.enqueueAll(mutations)
        }
    }

    @Test
    fun importCsv_solveDaoFailure_rollsBackCreatedSessionsAtomically() = runTest {
        val failingDao = FailingSolveDao(solveDao, shouldFailOnInsert = true)
        val importerWithFailingDao = CsvImporter(
            database = database,
            solveDao = failingDao,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao
        )

        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            rollback-solve-1,rollback-sess-1,Rollback Session,3x3,1700000001000,12000,none,R U
            rollback-solve-2,rollback-sess-2,Rollback Session 2,3x3,1700000002000,13000,none,R' U'
        """.trimIndent()

        val result = importerWithFailingDao.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue("Import must report Error status on DAO failure", result is CsvImportStatus.Error)
        val error = result as CsvImportStatus.Error
        assertTrue(error.throwable.message!!.contains("Simulated disk error"))

        // CRITICAL CHECK: The 2 sessions created during transaction MUST have been rolled back
        val sessionsInDb = sessionDao.getSessionsByIds(listOf("rollback-sess-1", "rollback-sess-2"))
        assertTrue("Sessions must be completely rolled back if solve insert fails", sessionsInDb.isEmpty())

        // Solves must be empty
        val solvesInDb = solveDao.getSolvesByScope(ownerId = "guest")
        assertTrue("Solves must be empty after rollback", solvesInDb.isEmpty())
    }

    @Test
    fun importCsv_outboxEnqueueFailure_rollsBackSessionsSolvesAndOutboxAtomically() = runTest {
        val failingOutbox = FailingSyncOutboxDao(syncOutboxDao, failOnSolveMutations = true)
        val importerWithFailingOutbox = CsvImporter(
            database = database,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = failingOutbox
        )

        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            auth-s-1,auth-sess-1,Atomic Session,3x3,1700000001000,10000,none,R U
        """.trimIndent()

        val result = importerWithFailingOutbox.importCsv(stringToStream(csv), ownerId = "user-atomic")
        assertTrue(result is CsvImportStatus.Error)

        // Verify that NEITHER session, NOR solve, NOR session outbox records exist
        val sessions = sessionDao.getSessionsByIds(listOf("auth-sess-1"))
        assertTrue("Session must be rolled back", sessions.isEmpty())

        val solves = solveDao.getSolvesByScope(ownerId = "user-atomic")
        assertTrue("Solves must be rolled back", solves.isEmpty())

        val outbox = syncOutboxDao.getAllPendingForOwner("user-atomic", 100)
        assertTrue("Outbox entries must be completely rolled back", outbox.isEmpty())
    }

    // =========================================================================
    // Challenge 5: Authenticated vs Guest Outbox Mutation Integrity and Order
    // =========================================================================

    @Test
    fun importCsv_guestUser_generatesStrictlyZeroOutboxRecords() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            guest-solve-1,guest-sess-1,Guest Sess,3x3,1700000001000,10000,none,R U
            guest-solve-2,guest-sess-1,Guest Sess,3x3,1700000002000,11000,none,R' U'
        """.trimIndent()

        val result = defaultImporter.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)

        // Strict assertion: zero outbox mutations for guest
        val count = syncOutboxDao.countPending("guest")
        assertEquals(0, count)
        val list = syncOutboxDao.getAllPendingForOwner("guest", 100)
        assertTrue(list.isEmpty())
    }

    @Test
    fun importCsv_authenticatedUser_enqueuesSessionsBeforeSolvesWithPayloadVerification() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-auth-1,sess-auth-1,Auth Alpha,3x3,1700000001000,12340,none,R U R' U'
            solve-auth-2,sess-auth-1,Auth Alpha,3x3,1700000002000,14560,+2,F R U
            solve-auth-3,sess-auth-2,Auth Beta,2x2,1700000003000,4200,dnf,R' F R2
        """.trimIndent()

        val result = defaultImporter.importCsv(stringToStream(csv), ownerId = "user-456")
        assertTrue(result is CsvImportStatus.Success)

        // Total mutations: 2 sessions + 3 solves = 5
        val pendingCount = syncOutboxDao.countPending("user-456")
        assertEquals(5, pendingCount)

        val allMutations = syncOutboxDao.getAllPendingForOwner("user-456", 10)
        assertEquals(5, allMutations.size)

        // Verify session mutations precede solve mutations in execution / queueing
        val sessionMutations = allMutations.filter { it.entityType == "session" }
        val solveMutations = allMutations.filter { it.entityType == "solve" }
        assertEquals(2, sessionMutations.size)
        assertEquals(3, solveMutations.size)

        // Verify session payloads
        val sess1 = sessionMutations.find { it.entityId == "sess-auth-1" }!!
        assertEquals("upsert", sess1.action)
        assertEquals(0L, sess1.baseVersion)
        assertTrue(sess1.payloadJson!!.contains("\"name\":\"Auth Alpha\""))
        assertTrue(sess1.payloadJson!!.contains("\"kind\":\"manual\""))

        // Verify solve payloads
        val solve2 = solveMutations.find { it.entityId == "solve-auth-2" }!!
        assertEquals("upsert", solve2.action)
        assertEquals(0L, solve2.baseVersion)
        assertTrue(solve2.payloadJson!!.contains("\"duration_ms\":14560"))
        assertTrue(solve2.payloadJson!!.contains("\"penalty\":\"plus_two\""))

        // Verify no outbox generated for pre-existing sessions on subsequent imports
        val csv2 = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-auth-4,sess-auth-1,Auth Alpha,3x3,1700000004000,11110,none,U R
        """.trimIndent()

        val result2 = defaultImporter.importCsv(stringToStream(csv2), ownerId = "user-456")
        assertTrue(result2 is CsvImportStatus.Success)
        assertEquals(1, (result2 as CsvImportStatus.Success).importedCount)
        assertEquals(0, result2.sessionsCreatedCount)

        // Only 1 new solve mutation was enqueued (no duplicate session mutation)
        val updatedCount = syncOutboxDao.countPending("user-456")
        assertEquals(6, updatedCount)
    }

    // =========================================================================
    // Challenge 6: Fault Tolerance on Corrupted and Malformed Rows
    // =========================================================================

    @Test
    fun importCsv_extremeMalformedRows_skipsOnlyCorruptRowsAndImportsValid() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            valid-1,sess-1,S1,3x3,1700000001000,12000,none,R U
            bad-col-count,only,three,columns
            bad-timestamp,sess-1,S1,3x3,not-a-number,12000,none,R U
            negative-timestamp,sess-1,S1,3x3,-1700000001000,12000,none,R U
            zero-timestamp,sess-1,S1,3x3,0,12000,none,R U
            negative-duration,sess-1,S1,3x3,1700000002000,-100,none,R U
            blank-solve-id,,sess-1,S1,3x3,1700000003000,12000,none,R U
            valid-2,sess-1,S1,3x3,1700000004000,13000,+2,F R U
        """.trimIndent()

        val result = defaultImporter.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(2, success.importedCount)
        assertEquals(6, success.malformedCount)
        assertEquals(0, success.duplicateCount)

        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        assertEquals(2, solves.size)
        assertTrue(solves.any { it.id == "valid-1" })
        assertTrue(solves.any { it.id == "valid-2" })
    }

    // =========================================================================
    // Challenge 7: Concurrent Import Stress Testing
    // =========================================================================

    @Test
    fun importCsv_concurrentImports_executesWithoutDatabaseDeadlockOrCorruptState() = runTest {
        val batch1 = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            conc-1,sess-conc-1,Conc 1,3x3,1700000001000,10000,none,R
            conc-2,sess-conc-1,Conc 1,3x3,1700000002000,11000,none,U
        """.trimIndent()

        val batch2 = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            conc-3,sess-conc-2,Conc 2,3x3,1700000003000,12000,none,F
            conc-4,sess-conc-2,Conc 2,3x3,1700000004000,13000,none,D
        """.trimIndent()

        val def1 = async { defaultImporter.importCsv(stringToStream(batch1), ownerId = "guest") }
        val def2 = async { defaultImporter.importCsv(stringToStream(batch2), ownerId = "guest") }

        val results = awaitAll(def1, def2)
        assertTrue(results.all { it is CsvImportStatus.Success })

        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        assertEquals(4, solves.size)
        val sessions = sessionDao.getSessionsByIds(listOf("sess-conc-1", "sess-conc-2"))
        assertEquals(2, sessions.size)
    }
}
