package com.maciekhetman.cubetimer.domain.csv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * The importer restores soft-deleted rows as copies of what it read, so it must read them inside the
 * transaction that writes them. Otherwise a sync that lands between the read and the write (bumping
 * `version`) is overwritten with the old row and the enqueued mutation carries a stale base version. A
 * concurrent writer can't be interleaved into a Room transaction deterministically, so this pins the
 * mechanism: every lookup happens in a transaction.
 */
@RunWith(RobolectricTestRunner::class)
class CsvImporterReadInTransactionTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: TransactionRecordingSolveDao
    private lateinit var sessionDao: TransactionRecordingSessionDao
    private var syncTriggers = 0
    private lateinit var importer: CsvImporter

    private val owner = "user-1"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        solveDao = TransactionRecordingSolveDao(database.solveDao(), database)
        sessionDao = TransactionRecordingSessionDao(database.sessionDao(), database)
        importer = CsvImporter(
            database = database,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncTrigger = { syncTriggers++ }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun stream(csv: String) = ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8))

    private fun assertAllLookupsInTransaction() {
        assertTrue("the importer looked up solves", solveDao.readsInTransaction.isNotEmpty())
        assertTrue(
            "every solve lookup happened inside the write's transaction: ${solveDao.readsInTransaction}",
            solveDao.readsInTransaction.all { it }
        )
        assertTrue("the importer looked up sessions", sessionDao.readsInTransaction.isNotEmpty())
        assertTrue(
            "every session lookup happened inside the write's transaction: ${sessionDao.readsInTransaction}",
            sessionDao.readsInTransaction.all { it }
        )
    }

    private val csv = """
        # Source: CubeTimer
        solve_id,session_id,session_name,puzzle,timestamp,time,penalty,scramble
        solve-1,sess-1,S1,3x3,1700000001000,12000,none,R U
    """.trimIndent()

    @Test
    fun restoringSoftDeletedRows_looksThemUpInsideTheTransaction() = runTest {
        database.sessionDao().insertAll(
            listOf(
                SessionEntity(
                    id = "sess-1",
                    ownerId = owner,
                    name = "S1",
                    startedAt = "2026-09-11T10:00:00.000Z",
                    version = 2L,
                    deletedAt = "2026-09-11T10:05:00.000Z"
                )
            )
        )
        database.solveDao().insertAll(
            listOf(
                SolveEntity(
                    id = "solve-1",
                    ownerId = owner,
                    sessionId = "sess-1",
                    durationMs = 9000L,
                    solvedAt = "2026-09-11T10:00:00.000Z",
                    version = 4L,
                    deletedAt = "2026-09-11T10:05:00.000Z"
                )
            )
        )

        val status = importer.importCsv(stream(csv), ownerId = owner)

        assertEquals(1, (status as CsvImportStatus.Success).importedCount)
        assertAllLookupsInTransaction()
        val mutations = database.syncOutboxDao().getAllPendingForOwner(owner)
        assertEquals(listOf(2L, 4L), mutations.map { it.baseVersion })
    }

    @Test
    fun importingNewRows_looksThemUpInsideTheTransaction() = runTest {
        val status = importer.importCsv(stream(csv), ownerId = owner)

        assertEquals(1, (status as CsvImportStatus.Success).importedCount)
        assertAllLookupsInTransaction()
    }

    @Test
    fun syncIsTriggeredOnlyWhenSomethingWasWritten() = runTest {
        importer.importCsv(stream(csv), ownerId = owner)
        assertEquals(1, syncTriggers)

        val again = importer.importCsv(stream(csv), ownerId = owner) as CsvImportStatus.Success

        assertEquals(0, again.importedCount)
        assertEquals(1, again.duplicateCount)
        assertEquals("a re-import that writes nothing does not schedule a sync", 1, syncTriggers)
    }
}

/** Real DAO that records, for every lookup, whether it ran inside a Room transaction. */
private class TransactionRecordingSolveDao(
    private val delegate: SolveDao,
    private val database: CubeDatabase
) : SolveDao by delegate {
    val readsInTransaction = mutableListOf<Boolean>()

    override suspend fun getSolvesByIds(ids: List<String>): List<SolveEntity> {
        readsInTransaction += database.inTransaction()
        return delegate.getSolvesByIds(ids)
    }
}

private class TransactionRecordingSessionDao(
    private val delegate: SessionDao,
    private val database: CubeDatabase
) : SessionDao by delegate {
    val readsInTransaction = mutableListOf<Boolean>()

    override suspend fun getSessionsByIds(ids: List<String>): List<SessionEntity> {
        readsInTransaction += database.inTransaction()
        return delegate.getSessionsByIds(ids)
    }
}
