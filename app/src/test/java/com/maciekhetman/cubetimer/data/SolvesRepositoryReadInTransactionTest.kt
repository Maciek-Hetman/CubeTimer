package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.toSolveTime
import com.maciekhetman.cubetimer.model.Penalty
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The read-modify-write methods must read the row inside the same Room transaction as the write.
 * Otherwise a sync that lands between the read and the write (bumping the row's `version` after an
 * accepted mutation) is overwritten with the old version, and the enqueued mutation carries a stale
 * base version - a self-conflict on the server. A concurrent writer can't be interleaved into a Room
 * transaction deterministically, so this pins the mechanism: every such read happens in a transaction.
 */
@RunWith(RobolectricTestRunner::class)
class SolvesRepositoryReadInTransactionTest {

    private lateinit var database: CubeDatabase
    private lateinit var solveDao: TransactionRecordingSolveDao
    private lateinit var repository: SolvesRepository

    private val owner = "user-1"
    private val row = SolveEntity(
        id = "solve-1",
        ownerId = owner,
        sessionId = null,
        event = "3x3",
        durationMs = 11_000L,
        solvedAt = "2026-09-29T10:00:00.000Z",
        version = 4L
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = CubeDatabase.createInMemory(context)
        solveDao = TransactionRecordingSolveDao(database.solveDao(), database)
        repository = SolvesRepository(
            context = context,
            solveDao = solveDao,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun assertAllReadsInTransaction() {
        assertTrue("the method read the row", solveDao.readsInTransaction.isNotEmpty())
        assertTrue(
            "every read of the row happened inside the write's transaction: ${solveDao.readsInTransaction}",
            solveDao.readsInTransaction.all { it }
        )
    }

    @Test
    fun saveSolve_readsTheExistingRowInsideTheTransaction() = runTest {
        database.solveDao().upsert(row)

        repository.saveSolve(row.toSolveTime().copy(penalty = Penalty.PLUS_TWO), ownerId = owner)

        assertAllReadsInTransaction()
        assertEquals(4L, database.solveDao().getSolveById(row.id)?.version)
    }

    @Test
    fun updateSolvePenalty_readsTheExistingRowInsideTheTransaction() = runTest {
        database.solveDao().upsert(row)

        repository.updateSolvePenalty(row.toSolveTime(), Penalty.DNF, ownerId = owner)

        assertAllReadsInTransaction()
        assertEquals(4L, database.syncOutboxDao().getPendingMutationForEntity(owner, "solve", row.id)?.baseVersion)
    }

    @Test
    fun deleteSolvesByIds_readsTheRowsInsideTheTransaction() = runTest {
        database.solveDao().upsert(row)

        val deleted = repository.deleteSolvesByIds(listOf(row.id), ownerId = owner)

        assertEquals(listOf(row.id), deleted.map { it.id })
        assertAllReadsInTransaction()
    }

    @Test
    fun restoreSolves_readsTheRowsInsideTheTransaction() = runTest {
        database.solveDao().upsert(row.copy(deletedAt = "2026-09-29T11:00:00.000Z"))

        repository.restoreSolves(listOf(row.toSolveTime()), ownerId = owner)

        assertAllReadsInTransaction()
        assertEquals(null, database.solveDao().getSolveById(row.id)?.deletedAt)
    }

    @Test
    fun deleteSolvesByIds_withNothingToDelete_doesNotTriggerSync() = runTest {
        var syncTriggers = 0
        val triggeringRepository = SolvesRepository(
            context = ApplicationProvider.getApplicationContext(),
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            syncTrigger = { syncTriggers++ }
        )

        val deleted = triggeringRepository.deleteSolvesByIds(listOf("missing"), ownerId = owner)

        assertTrue(deleted.isEmpty())
        assertEquals(0, syncTriggers)
    }
}

/** Real DAO that records, for every row read, whether it ran inside a Room transaction. */
private class TransactionRecordingSolveDao(
    private val delegate: SolveDao,
    private val database: CubeDatabase
) : SolveDao by delegate {
    val readsInTransaction = mutableListOf<Boolean>()

    override suspend fun getSolveById(id: String): SolveEntity? {
        readsInTransaction += database.inTransaction()
        return delegate.getSolveById(id)
    }

    override suspend fun getSolvesByIds(ids: List<String>): List<SolveEntity> {
        readsInTransaction += database.inTransaction()
        return delegate.getSolvesByIds(ids)
    }
}
