package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.SessionKind
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * History lists solves by session, so legacy DataStore solves must land in sessions on import, and
 * installs that already imported them session-less (older builds) are repaired on start.
 */
@RunWith(RobolectricTestRunner::class)
class DataStoreMigrationSessionAttachTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var migration: DataStoreMigration

    private val solvesListKey = stringPreferencesKey("solves_list")

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
        database = CubeDatabase.createInMemory(context)
        migration = DataStoreMigration(context, database, zoneId = ZoneOffset.UTC)
    }

    @After
    fun tearDown() = runTest {
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
        database.close()
    }

    private suspend fun markAlreadyMigrated() {
        context.settingsDataStore.edit { it[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY] = true }
    }

    private fun orphan(id: String, ownerId: String = "guest", solvedAt: String, deletedAt: String? = null) =
        SolveEntity(
            id = id, ownerId = ownerId, sessionId = null, event = "3x3", durationMs = 10_000L,
            solvedAt = solvedAt, version = 0L, updatedAt = solvedAt, deletedAt = deletedAt
        )

    @Test
    fun legacyImport_putsSolvesInClosedAutomaticSessionsSplitByEventAndInactivityGap() = runTest {
        // 3x3: 06:40, 06:41, 07:41 (exactly the 60 min gap: same session), 09:00 (new session).
        // 2x2: 06:42, its own session with the same base name (names are unique per event).
        context.solvesDataStore.edit {
            it[solvesListKey] = """
                [
                  {"id":"a","timeInMillis":12000,"penalty":"NONE","timestamp":1725000000000,"mode":"CUBE_3x3"},
                  {"id":"b","timeInMillis":13000,"penalty":"DNF","timestamp":1725000060000,"mode":"CUBE_3x3"},
                  {"id":"c","timeInMillis":11000,"penalty":"NONE","timestamp":1725003660000,"mode":"CUBE_3x3"},
                  {"id":"d","timeInMillis":14000,"penalty":"NONE","timestamp":1725008400000,"mode":"CUBE_3x3"},
                  {"id":"e","timeInMillis":4000,"penalty":"NONE","timestamp":1725000120000,"mode":"CUBE_2x2"}
                ]
            """.trimIndent()
        }

        migration.migrateIfNeeded()

        val solves = database.solveDao().getAllActiveSolvesForOwner("guest").associateBy { it.id }
        assertEquals(5, solves.size)
        assertTrue(solves.values.all { it.sessionId != null })

        val sessions = database.sessionDao().getAllActiveSessionsForOwner("guest").associateBy { it.id }
        assertEquals(3, sessions.size)
        sessions.values.forEach { session ->
            assertEquals(SessionKind.AUTOMATIC.value, session.kind)
            assertNotNull("imported sessions must be closed", session.endedAt)
        }

        val first3x3 = sessions.getValue(solves.getValue("a").sessionId!!)
        assertEquals("30 aug 2024 morning", first3x3.name)
        assertEquals("2024-08-30T06:40:00.000Z", first3x3.startedAt)
        assertEquals("2024-08-30T07:41:00.000Z", first3x3.endedAt)
        assertEquals(first3x3.id, solves.getValue("b").sessionId)
        assertEquals(first3x3.id, solves.getValue("c").sessionId)

        val second3x3 = sessions.getValue(solves.getValue("d").sessionId!!)
        assertEquals("30 aug 2024 morning 2", second3x3.name)
        assertEquals("2024-08-30T09:00:00.000Z", second3x3.startedAt)
        assertEquals("2024-08-30T09:00:00.000Z", second3x3.endedAt)

        val session2x2 = sessions.getValue(solves.getValue("e").sessionId!!)
        assertEquals("2x2", session2x2.event)
        assertEquals("30 aug 2024 morning", session2x2.name)

        // Visible in History, nothing queued for sync, and no open session for the timer to reuse.
        val history = database.sessionDao().observeSessionsWithStats("guest").first()
        assertEquals(5, history.sumOf { it.solveCount })
        assertEquals(0, database.syncOutboxDao().countPending("guest"))
        assertNull(database.sessionDao().getOpenAutomaticSession("guest", "3x3"))
    }

    @Test
    fun alreadyMigratedInstall_attachesGuestOrphansOnly_andIsIdempotent() = runTest {
        markAlreadyMigrated()
        database.sessionDao().insert(
            SessionEntity(
                id = "existing", ownerId = "guest", name = "30 aug 2024 morning", event = "3x3",
                kind = SessionKind.AUTOMATIC.value, startedAt = "2024-08-30T05:00:00.000Z",
                endedAt = "2024-08-30T05:10:00.000Z"
            )
        )
        database.solveDao().insertAll(
            listOf(
                orphan("g-1", solvedAt = "2024-08-30T06:40:00.000Z"),
                // One millisecond past the inactivity gap: a separate session.
                orphan("g-2", solvedAt = "2024-08-30T07:40:00.001Z"),
                orphan("g-deleted", solvedAt = "2024-08-30T06:45:00.000Z", deletedAt = "2024-09-01T00:00:00.000Z"),
                orphan("u-1", ownerId = "user-1", solvedAt = "2024-08-30T06:40:00.000Z")
            )
        )

        migration.migrateIfNeeded()

        val solveDao = database.solveDao()
        val sessionDao = database.sessionDao()
        val s1 = sessionDao.getSessionById(solveDao.getSolveById("g-1")!!.sessionId!!)!!
        val s2 = sessionDao.getSessionById(solveDao.getSolveById("g-2")!!.sessionId!!)!!
        assertEquals("30 aug 2024 morning 2", s1.name)
        assertEquals("30 aug 2024 morning 3", s2.name)
        assertTrue(solveDao.getActiveSolvesWithoutSession("guest").isEmpty())

        // Soft-deleted rows and signed-in owners' rows are left alone.
        assertNull(solveDao.getSolveById("g-deleted")!!.sessionId)
        assertNull(solveDao.getSolveById("u-1")!!.sessionId)
        assertTrue(sessionDao.getAllSessionsForOwner("user-1").isEmpty())
        assertEquals(0, database.syncOutboxDao().countPending("guest"))
        assertEquals(0, database.syncOutboxDao().countPending("user-1"))

        // Every start runs the repair; with nothing left to attach it changes nothing.
        val solvesBefore = solveDao.getAllSolvesForOwner("guest").sortedBy { it.id }
        val sessionsBefore = sessionDao.getAllSessionsForOwner("guest").sortedBy { it.id }
        migration.migrateIfNeeded()
        assertEquals(solvesBefore, solveDao.getAllSolvesForOwner("guest").sortedBy { it.id })
        assertEquals(sessionsBefore, sessionDao.getAllSessionsForOwner("guest").sortedBy { it.id })
    }
}
