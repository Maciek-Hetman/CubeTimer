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
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CsvImporterTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var importer: CsvImporter

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        solveDao = database.solveDao()
        sessionDao = database.sessionDao()
        syncOutboxDao = database.syncOutboxDao()
        importer = CsvImporter(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun stringToStream(content: String) =
        ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun importCsv_validInput_importsSolvesAndRecreatesSessions() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-1,sess-1,Session One,3x3,1700000001000,12340,none,R U R' U'
            solve-2,sess-1,Session One,3x3,1700000005000,14200,+2,F R U R'
            solve-3,sess-2,Session Two,2x2,1700000010000,3500,dnf,R' F R2
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(3, success.importedCount)
        assertEquals(2, success.sessionsCreatedCount)
        assertEquals(0, success.duplicateCount)
        assertEquals(0, success.malformedCount)

        // Verify sessions created in DB
        val sessions = sessionDao.getSessionsByIds(listOf("sess-1", "sess-2"))
        assertEquals(2, sessions.size)
        val s1 = sessions.find { it.id == "sess-1" }!!
        assertEquals("Session One", s1.name)
        assertEquals("3x3", s1.event)
        assertEquals(SessionKind.MANUAL.value, s1.kind)

        // Verify solves in DB
        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        assertEquals(3, solves.size)
        val solve2 = solves.find { it.id == "solve-2" }!!
        assertEquals(14200L, solve2.durationMs)
        assertEquals("plus_two", solve2.penalty)

        // Guest user generates ZERO outbox entries
        val outbox = syncOutboxDao.getPendingMutations("guest")
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun importCsv_authenticatedUser_enqueuesSessionAndSolveMutations() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-a,sess-auth,Main Session,3x3,1700000001000,11000,none,R U R'
            solve-b,sess-auth,Main Session,3x3,1700000002000,12000,none,U R U'
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "user-123")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(2, success.importedCount)
        assertEquals(1, success.sessionsCreatedCount)

        // Verify outbox mutations: 1 session upsert + 2 solve upserts
        val mutations = syncOutboxDao.getPendingMutations("user-123")
        assertEquals(3, mutations.size)

        val sessionMutation = mutations.find { it.entityType == "session" }
        assertNotNull(sessionMutation)
        assertEquals("sess-auth", sessionMutation!!.entityId)
        assertEquals("upsert", sessionMutation.action)
        assertEquals(0L, sessionMutation.baseVersion)
        assertTrue(sessionMutation.payloadJson!!.contains("\"name\":\"Main Session\""))

        val solveMutations = mutations.filter { it.entityType == "solve" }
        assertEquals(2, solveMutations.size)
        assertTrue(solveMutations.all { it.action == "upsert" && it.baseVersion == 0L })
    }

    @Test
    fun importCsv_tolerantMissingComment_importsSuccessfully() = runTest {
        val csv = """
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-1,sess-1,Session One,3x3,1700000001000,12000,none,R U R' U'
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        assertEquals(1, (result as CsvImportStatus.Success).importedCount)
    }

    @Test
    fun importCsv_rejectsInvalidComment() = runTest {
        val csv = """
            # cSTimer Export
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-1,sess-1,Session One,3x3,1700000001000,12000,none,R U R' U'
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.InvalidFile)
        assertTrue((result as CsvImportStatus.InvalidFile).reason.contains("Invalid comment header"))
        assertTrue(solveDao.getSolvesByScope(ownerId = "guest").isEmpty())
    }

    @Test
    fun importCsv_rejectsMissingRequiredColumn() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,time,penalty,scramble
            solve-1,sess-1,Session One,3x3,12000,none,R U R' U'
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.InvalidFile)
        assertTrue((result as CsvImportStatus.InvalidFile).reason.contains("timestamp"))
        assertTrue(solveDao.getSolvesByScope(ownerId = "guest").isEmpty())
    }

    @Test
    fun importCsv_emptyStream_returnsEmptyFile() = runTest {
        val result = importer.importCsv(stringToStream(""), ownerId = "guest")
        assertEquals(CsvImportStatus.EmptyFile, result)
    }

    @Test
    fun importCsv_whitespaceOnly_returnsEmptyFile() = runTest {
        val result = importer.importCsv(stringToStream("\n  \r\n \n"), ownerId = "guest")
        assertEquals(CsvImportStatus.EmptyFile, result)
    }

    @Test
    fun importCsv_headerOnlyNoSolves_returnsSuccessZero() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success
        assertEquals(0, success.importedCount)
        assertEquals(0, success.duplicateCount)
        assertEquals(0, success.malformedCount)
        assertEquals(0, success.sessionsCreatedCount)
    }

    @Test
    fun importCsv_dbDuplicates_skipsExistingSolvesWithoutOverwrite() = runTest {
        // Pre-populate DB with solve-1 having duration 15000L
        sessionDao.insertAll(listOf(
            SessionEntity(id = "sess-1", ownerId = "guest", name = "S1", event = "3x3", startedAt = "2026-09-11T10:00:00.000Z")
        ))
        solveDao.insertAll(listOf(
            SolveEntity(id = "solve-1", ownerId = "guest", sessionId = "sess-1", event = "3x3", durationMs = 15000L, solvedAt = "2026-09-11T10:00:00.000Z")
        ))

        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-1,sess-1,S1,3x3,1700000001000,9999,none,R U R'
            solve-2,sess-1,S1,3x3,1700000002000,12000,none,F R U
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(1, success.importedCount)
        assertEquals(1, success.duplicateCount)

        // Existing solve-1 must NOT be overwritten
        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        val s1 = solves.find { it.id == "solve-1" }!!
        assertEquals(15000L, s1.durationMs)
    }

    @Test
    fun importCsv_intraFileDuplicates_skipsDuplicateOccurrences() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-same,sess-1,Session,3x3,1700000001000,10000,none,R U
            solve-same,sess-1,Session,3x3,1700000002000,11000,none,R' U'
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(1, success.importedCount)
        assertEquals(1, success.duplicateCount)

        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        assertEquals(1, solves.size)
        assertEquals(10000L, solves[0].durationMs)
    }

    @Test
    fun importCsv_malformedRows_skipsBadRowsAndImportsGood() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-good-1,sess-1,S1,3x3,1700000001000,10000,none,R U
            too,few,columns
            solve-bad-ts,sess-1,S1,3x3,not_a_number,10000,none,R U
            solve-bad-time,sess-1,S1,3x3,1700000002000,-500,none,R U
            ,sess-1,S1,3x3,1700000003000,10000,none,R U
            solve-good-2,sess-1,S1,3x3,1700000004000,11000,none,R' U'
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(2, success.importedCount)
        assertEquals(4, success.malformedCount)
        assertEquals(0, success.duplicateCount)

        val solves = solveDao.getSolvesByScope(ownerId = "guest")
        assertEquals(2, solves.size)
    }

    @Test
    fun importCsv_existingSessionInDb_preservesSessionProperties() = runTest {
        sessionDao.insertAll(listOf(
            SessionEntity(
                id = "sess-pre",
                ownerId = "guest",
                name = "Original Name",
                event = "3x3",
                kind = SessionKind.AUTOMATIC.value,
                startedAt = "2026-09-11T09:00:00.000Z"
            )
        ))

        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-new,sess-pre,Attempted Rename,3x3,1700000001000,12000,none,R U
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(1, success.importedCount)
        assertEquals(0, success.sessionsCreatedCount)

        // Existing session properties must be preserved
        val sess = sessionDao.getSessionsByIds(listOf("sess-pre")).first()
        assertEquals("Original Name", sess.name)
        assertEquals(SessionKind.AUTOMATIC.value, sess.kind)
    }

    @Test
    fun importCsv_specialCharactersScramble_preservedExactly() = runTest {
        val complexScramble = "[R, U] \"Megaminx\" \n D++"
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-spec,sess-1,Special,"3x3",1700000001000,15000,none,"[R, U] ""Megaminx"" 
             D++"
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)

        val solve = solveDao.getSolvesByScope(ownerId = "guest").first()
        assertEquals(
            "[R, U] \"Megaminx\" \n D++",
            solve.scramble.replace("\r\n", "\n")
        )
    }

    @Test
    fun importCsv_missingSessionTimestamps_boundsEarliestAndLatest() = runTest {
        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-1,sess-bounds,Session Bounds,3x3,1700000005000,10000,none,R
            solve-2,sess-bounds,Session Bounds,3x3,1700000001000,11000,none,U
            solve-3,sess-bounds,Session Bounds,3x3,1700000003000,12000,none,F
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)

        val session = sessionDao.getSessionsByIds(listOf("sess-bounds")).first()
        assertEquals(CubeTypeConverters.epochMillisToIso(1700000001000L), session.startedAt)
        assertEquals(CubeTypeConverters.epochMillisToIso(1700000005000L), session.endedAt)
    }

    @Test
    fun importCsv_utf8BomPrefix_importsSuccessfully() = runTest {
        val csv = "\uFEFF# Source: CubeTimer\r\nsolve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble\r\nsolve-bom,sess-1,S1,3x3,1700000001000,10000,none,R U"

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        assertEquals(1, (result as CsvImportStatus.Success).importedCount)
    }

    @Test
    fun importCsv_reorderedColumns_importsSuccessfully() = runTest {
        val csv = """
            # Source: CubeTimer
            scramble,time,timestamp,penalty,puzzle,session_name,session_id,solve_id
            R U R' U',13500,1700000001000,+2,4x4,Custom Order,sess-reorder,solve-reorder
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)

        val solve = solveDao.getSolvesByScope(ownerId = "guest").first()
        assertEquals("solve-reorder", solve.id)
        assertEquals("sess-reorder", solve.sessionId)
        assertEquals("4x4", solve.event)
        assertEquals(13500L, solve.durationMs)
        assertEquals("plus_two", solve.penalty)
        assertEquals("R U R' U'", solve.scramble)
    }

    @Test
    fun importCsv_softDeletedSolvesInDb_treatedAsDuplicates() = runTest {
        sessionDao.insertAll(listOf(
            SessionEntity(id = "sess-1", ownerId = "guest", name = "S1", event = "3x3", startedAt = "2026-09-11T10:00:00.000Z")
        ))
        solveDao.insertAll(listOf(
            SolveEntity(
                id = "solve-del",
                ownerId = "guest",
                sessionId = "sess-1",
                event = "3x3",
                durationMs = 12000L,
                solvedAt = "2026-09-11T10:00:00.000Z",
                deletedAt = "2026-09-11T10:05:00.000Z"
            )
        ))

        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-del,sess-1,S1,3x3,1700000001000,12000,none,R U
        """.trimIndent()

        val result = importer.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(0, success.importedCount)
        assertEquals(1, success.duplicateCount)
    }

    @Test
    fun importCsv_exceeds999SqliteParams_chunksSuccessfully() = runTest {
        val count = 1200
        val sb = StringBuilder()
        sb.append("# Source: CubeTimer\r\n")
        sb.append(CsvFormat.HEADER_LINE).append("\r\n")
        for (i in 1..count) {
            sb.append("solve-$i,sess-large,Large Session,3x3,${1700000000000L + i},10000,none,R U\r\n")
        }

        val result = importer.importCsv(stringToStream(sb.toString()), ownerId = "guest")
        assertTrue(result is CsvImportStatus.Success)
        val success = result as CsvImportStatus.Success

        assertEquals(count, success.importedCount)
        assertEquals(0, success.duplicateCount)
        assertEquals(0, success.malformedCount)
        assertEquals(1, success.sessionsCreatedCount)
    }

    @Test
    fun importCsv_syncTriggerInvoked() = runTest {
        var triggerInvoked = false
        val importerWithTrigger = CsvImporter(database, syncTrigger = { triggerInvoked = true })

        val csv = """
            # Source: CubeTimer
            solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
            solve-trigger,sess-1,S1,3x3,1700000001000,10000,none,R U
        """.trimIndent()

        importerWithTrigger.importCsv(stringToStream(csv), ownerId = "guest")
        assertTrue(triggerInvoked)
    }
}
