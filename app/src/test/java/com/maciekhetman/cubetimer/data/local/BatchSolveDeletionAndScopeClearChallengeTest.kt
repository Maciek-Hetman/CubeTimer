package com.maciekhetman.cubetimer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.Instant

/**
 * Adversarial empirical challenge tests targeting:
 * 1. Batch solve deletion (`deleteSolvesByIds`) with empty lists, non-existent IDs, duplicate IDs, already soft-deleted IDs, and owner isolation.
 * 2. Scope clearing (`clearAllSolvesInScope`) across modes, all-mode clearing, and roundtrip fidelity via `restoreSolves`.
 * 3. Deduplication (`getExistingSolveIds`) with active, soft-deleted, and uninserted IDs.
 */
@RunWith(RobolectricTestRunner::class)
class BatchSolveDeletionAndScopeClearChallengeTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var solveDao: SolveDao
    private lateinit var sessionDao: SessionDao
    private lateinit var syncOutboxDao: SyncOutboxDao
    private lateinit var repository: SolvesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        solveDao = database.solveDao()
        sessionDao = database.sessionDao()
        syncOutboxDao = database.syncOutboxDao()

        repository = SolvesRepository(
            context = context,
            solveDao = solveDao,
            sessionDao = sessionDao,
            syncOutboxDao = syncOutboxDao,
            database = database
        )
    }

    @After
    fun tearDown() = runTest {
        context.solvesDataStore.edit { it.clear() }
        database.close()
    }

    // =========================================================================
    // 1. deleteSolvesByIds CHALLENGES
    // =========================================================================

    @Test
    fun deleteSolvesByIds_emptyList_returnsEmptyAndNoSideEffects() = runTest {
        // Prepopulate database with one solve
        val solve = SolveTime(id = "s-existing", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        repository.saveSolve(solve, ownerId = "user-empty-test")
        syncOutboxDao.clearOutbox("user-empty-test")

        val deleted = repository.deleteSolvesByIds(emptyList(), ownerId = "user-empty-test")
        assertTrue("Empty list input must return empty deleted list", deleted.isEmpty())

        // Verify solve remains untouched
        val active = repository.getAllActiveSolves("user-empty-test")
        assertEquals(1, active.size)
        assertEquals("s-existing", active[0].id)

        // Verify no outbox mutations generated
        val outbox = syncOutboxDao.getPendingMutations("user-empty-test")
        assertTrue("No outbox records should be produced for empty input", outbox.isEmpty())
    }

    @Test
    fun deleteSolvesByIds_nonExistentIds_returnsEmptyAndNoSideEffects() = runTest {
        val solve = SolveTime(id = "s-existing", timeInMillis = 10500L, mode = Mode.CUBE_3x3)
        repository.saveSolve(solve, ownerId = "user-nonexist")
        syncOutboxDao.clearOutbox("user-nonexist")

        val deleted = repository.deleteSolvesByIds(
            listOf("non-existent-1", "non-existent-2", "ghost-id"),
            ownerId = "user-nonexist"
        )
        assertTrue("Purely non-existent IDs must return empty list", deleted.isEmpty())

        // Existing solve unaffected
        val active = repository.getAllActiveSolves("user-nonexist")
        assertEquals(1, active.size)
        assertEquals("s-existing", active[0].id)

        // Outbox remains empty
        val outbox = syncOutboxDao.getPendingMutations("user-nonexist")
        assertTrue("No outbox records should be produced for non-existent IDs", outbox.isEmpty())
    }

    @Test
    fun deleteSolvesByIds_duplicateIdsInInput_returnsDeduplicatedDeletedSolvesAndSingleOutboxRecord() = runTest {
        val s1 = SolveTime(id = "s-dup-1", timeInMillis = 9500L, mode = Mode.CUBE_3x3)
        val s2 = SolveTime(id = "s-dup-2", timeInMillis = 11200L, mode = Mode.CUBE_3x3)
        repository.saveSolve(s1, ownerId = "user-dup")
        repository.saveSolve(s2, ownerId = "user-dup")
        syncOutboxDao.clearOutbox("user-dup")

        // Pass duplicates in the input list
        val inputWithDuplicates = listOf(
            "s-dup-1", "s-dup-1", "s-dup-1",
            "s-dup-2", "s-dup-2", "s-dup-1"
        )
        val deleted = repository.deleteSolvesByIds(inputWithDuplicates, ownerId = "user-dup")

        // Must return exactly 2 unique deleted solves, not duplicates
        assertEquals(2, deleted.size)
        val deletedIds = deleted.map { it.id }.toSet()
        assertEquals(setOf("s-dup-1", "s-dup-2"), deletedIds)

        // Verify DB state: both are soft-deleted
        val active = repository.getAllActiveSolves("user-dup")
        assertTrue(active.isEmpty())

        // Verify outbox: exactly 2 delete mutations (one per unique solve, not duplicated)
        val outbox = syncOutboxDao.getPendingMutations("user-dup")
        assertEquals(2, outbox.size)
        assertEquals(setOf("s-dup-1", "s-dup-2"), outbox.map { it.entityId }.toSet())
        assertTrue(outbox.all { it.action == "delete" && it.entityType == "solve" })
    }

    @Test
    fun deleteSolvesByIds_alreadySoftDeletedSolves_doesNotReturnOrDuplicateOutbox() = runTest {
        val s1 = SolveTime(id = "s-active", timeInMillis = 12000L, mode = Mode.CUBE_3x3)
        val s2 = SolveTime(id = "s-already-deleted", timeInMillis = 13000L, mode = Mode.CUBE_3x3)
        repository.saveSolve(s1, ownerId = "user-softdel")
        repository.saveSolve(s2, ownerId = "user-softdel")

        // Soft-delete s2 ahead of time
        val firstDeleted = repository.deleteSolvesByIds(listOf("s-already-deleted"), ownerId = "user-softdel")
        assertEquals(1, firstDeleted.size)
        assertEquals("s-already-deleted", firstDeleted[0].id)

        syncOutboxDao.clearOutbox("user-softdel")

        // Now attempt to delete s1 (active) and s2 (already soft-deleted) together
        val secondDeleted = repository.deleteSolvesByIds(
            listOf("s-active", "s-already-deleted"),
            ownerId = "user-softdel"
        )

        // Only s-active should be returned, NOT s-already-deleted
        assertEquals(1, secondDeleted.size)
        assertEquals("s-active", secondDeleted[0].id)

        // Outbox should contain ONLY 1 delete mutation for s-active
        val outbox = syncOutboxDao.getPendingMutations("user-softdel")
        assertEquals(1, outbox.size)
        assertEquals("s-active", outbox[0].entityId)

        // Third attempt: delete both again when both are soft-deleted
        syncOutboxDao.clearOutbox("user-softdel")
        val thirdDeleted = repository.deleteSolvesByIds(
            listOf("s-active", "s-already-deleted"),
            ownerId = "user-softdel"
        )
        assertTrue("Attempting to delete already-deleted solves must return empty list", thirdDeleted.isEmpty())
        assertTrue("No outbox records should be produced for already-deleted solves",
            syncOutboxDao.getPendingMutations("user-softdel").isEmpty())
    }

    @Test
    fun deleteSolvesByIds_adversarialCombination_mixedActiveDeletedGhostAndDuplicates() = runTest {
        val sActive1 = SolveTime(id = "s-act-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val sActive2 = SolveTime(id = "s-act-2", timeInMillis = 11000L, mode = Mode.CUBE_3x3)
        val sDeleted = SolveTime(id = "s-del-x", timeInMillis = 12000L, mode = Mode.CUBE_3x3)

        repository.saveSolve(sActive1, ownerId = "user-combo")
        repository.saveSolve(sActive2, ownerId = "user-combo")
        repository.saveSolve(sDeleted, ownerId = "user-combo")

        // Mark sDeleted as soft-deleted
        repository.deleteSolvesByIds(listOf("s-del-x"), ownerId = "user-combo")
        syncOutboxDao.clearOutbox("user-combo")

        // Mixed payload:
        // - "s-act-1" (duplicate active)
        // - "s-act-2" (active)
        // - "s-del-x" (duplicate soft-deleted)
        // - "ghost-1", "ghost-2" (non-existent)
        val adversarialInput = listOf(
            "s-act-1", "ghost-1", "s-del-x", "s-act-1", "s-act-2",
            "ghost-2", "s-del-x", "ghost-1", "s-act-2"
        )

        val deleted = repository.deleteSolvesByIds(adversarialInput, ownerId = "user-combo")

        assertEquals(2, deleted.size)
        assertEquals(setOf("s-act-1", "s-act-2"), deleted.map { it.id }.toSet())

        val outbox = syncOutboxDao.getPendingMutations("user-combo")
        assertEquals(2, outbox.size)
        assertEquals(setOf("s-act-1", "s-act-2"), outbox.map { it.entityId }.toSet())
    }

    @Test
    fun deleteSolvesByIds_ownerIsolation_doesNotDeleteOtherUserSolves() = runTest {
        val solveUserA = SolveTime(id = "s-user-a", timeInMillis = 8500L, mode = Mode.CUBE_3x3)
        val solveUserB = SolveTime(id = "s-user-b", timeInMillis = 9000L, mode = Mode.CUBE_3x3)
        repository.saveSolve(solveUserA, ownerId = "user-a")
        repository.saveSolve(solveUserB, ownerId = "user-b")
        syncOutboxDao.clearOutbox("user-a")
        syncOutboxDao.clearOutbox("user-b")

        // User A maliciously or mistakenly includes User B's solve ID
        val deleted = repository.deleteSolvesByIds(
            listOf("s-user-a", "s-user-b"),
            ownerId = "user-a"
        )

        // Only User A's solve is returned and deleted
        assertEquals(1, deleted.size)
        assertEquals("s-user-a", deleted[0].id)

        // User B's solve remains completely active
        val activeB = repository.getAllActiveSolves("user-b")
        assertEquals(1, activeB.size)
        assertEquals("s-user-b", activeB[0].id)

        // User B's outbox is empty
        val outboxB = syncOutboxDao.getPendingMutations("user-b")
        assertTrue(outboxB.isEmpty())

        // User A's outbox contains only User A's solve
        val outboxA = syncOutboxDao.getPendingMutations("user-a")
        assertEquals(1, outboxA.size)
        assertEquals("s-user-a", outboxA[0].entityId)
    }

    // =========================================================================
    // 2. clearAllSolvesInScope CHALLENGES
    // =========================================================================

    @Test
    fun clearAllSolvesInScope_modeScoping_preservesOtherEvents() = runTest {
        val ownerId = "user-scoping"

        // Insert solves across all 6 supported puzzle modes
        val s3x3_1 = SolveTime(id = "s-3x3-a", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val s3x3_2 = SolveTime(id = "s-3x3-b", timeInMillis = 11000L, mode = Mode.CUBE_3x3)
        val s3x3_3 = SolveTime(id = "s-3x3-c", timeInMillis = 12000L, mode = Mode.CUBE_3x3)

        val s2x2_1 = SolveTime(id = "s-2x2-a", timeInMillis = 3500L, mode = Mode.CUBE_2x2)
        val s2x2_2 = SolveTime(id = "s-2x2-b", timeInMillis = 4000L, mode = Mode.CUBE_2x2)

        val s4x4_1 = SolveTime(id = "s-4x4-a", timeInMillis = 45000L, mode = Mode.CUBE_4x4)
        val s4x4_2 = SolveTime(id = "s-4x4-b", timeInMillis = 48000L, mode = Mode.CUBE_4x4)

        val s5x5 = SolveTime(id = "s-5x5-a", timeInMillis = 95000L, mode = Mode.CUBE_5x5)
        val sPyra = SolveTime(id = "s-pyra-a", timeInMillis = 6000L, mode = Mode.PYRAMINX)
        val sMega = SolveTime(id = "s-mega-a", timeInMillis = 75000L, mode = Mode.MEGAMINX)

        val allSolves = listOf(s3x3_1, s3x3_2, s3x3_3, s2x2_1, s2x2_2, s4x4_1, s4x4_2, s5x5, sPyra, sMega)
        for (s in allSolves) {
            repository.saveSolve(s, ownerId = ownerId)
        }
        syncOutboxDao.clearOutbox(ownerId)

        // Clear ONLY Mode.CUBE_3x3
        val deleted3x3 = repository.clearAllSolvesInScope(mode = Mode.CUBE_3x3, ownerId = ownerId)

        // Verify returned list has exactly the 3 3x3 solves
        assertEquals(3, deleted3x3.size)
        assertEquals(setOf("s-3x3-a", "s-3x3-b", "s-3x3-c"), deleted3x3.map { it.id }.toSet())
        assertTrue(deleted3x3.all { it.mode == Mode.CUBE_3x3 })

        // Verify remaining active solves in DB: 2x2, 4x4, 5x5, Pyraminx, Megaminx must remain intact
        val remaining = repository.getAllActiveSolves(ownerId)
        assertEquals(7, remaining.size)
        assertFalse(remaining.any { it.mode == Mode.CUBE_3x3 })
        assertTrue(remaining.any { it.id == "s-2x2-a" })
        assertTrue(remaining.any { it.id == "s-2x2-b" })
        assertTrue(remaining.any { it.id == "s-4x4-a" })
        assertTrue(remaining.any { it.id == "s-4x4-b" })
        assertTrue(remaining.any { it.id == "s-5x5-a" })
        assertTrue(remaining.any { it.id == "s-pyra-a" })
        assertTrue(remaining.any { it.id == "s-mega-a" })

        // Outbox contains only the 3 3x3 delete mutations
        val outbox = syncOutboxDao.getPendingMutations(ownerId)
        assertEquals(3, outbox.size)
        assertEquals(setOf("s-3x3-a", "s-3x3-b", "s-3x3-c"), outbox.map { it.entityId }.toSet())

        // Clear Mode.PYRAMINX
        syncOutboxDao.clearOutbox(ownerId)
        val deletedPyra = repository.clearAllSolvesInScope(mode = Mode.PYRAMINX, ownerId = ownerId)
        assertEquals(1, deletedPyra.size)
        assertEquals("s-pyra-a", deletedPyra[0].id)

        val remainingAfterPyra = repository.getAllActiveSolves(ownerId)
        assertEquals(6, remainingAfterPyra.size)
        assertFalse(remainingAfterPyra.any { it.mode == Mode.PYRAMINX })
    }

    @Test
    fun clearAllSolvesInScope_allModesNull_cleansEverythingAcrossAllEventsAndIsolatesOwner() = runTest {
        val ownerUser = "user-clear-all"
        val otherUser = "user-unaffected"

        val s1 = SolveTime(id = "s-all-1", timeInMillis = 10000L, mode = Mode.CUBE_3x3)
        val s2 = SolveTime(id = "s-all-2", timeInMillis = 5000L, mode = Mode.CUBE_2x2)
        val s3 = SolveTime(id = "s-all-3", timeInMillis = 55000L, mode = Mode.CUBE_4x4)
        val sOther = SolveTime(id = "s-other-1", timeInMillis = 12000L, mode = Mode.CUBE_3x3)

        repository.saveSolve(s1, ownerId = ownerUser)
        repository.saveSolve(s2, ownerId = ownerUser)
        repository.saveSolve(s3, ownerId = ownerUser)
        repository.saveSolve(sOther, ownerId = otherUser)
        syncOutboxDao.clearOutbox(ownerUser)
        syncOutboxDao.clearOutbox(otherUser)

        // Clear with mode = null (all events for ownerUser)
        val cleared = repository.clearAllSolvesInScope(mode = null, ownerId = ownerUser)

        assertEquals(3, cleared.size)
        assertEquals(setOf("s-all-1", "s-all-2", "s-all-3"), cleared.map { it.id }.toSet())

        // Verify ownerUser has 0 active solves
        val activeOwner = repository.getAllActiveSolves(ownerUser)
        assertTrue(activeOwner.isEmpty())

        // Verify otherUser's solve remains completely active and untouched
        val activeOther = repository.getAllActiveSolves(otherUser)
        assertEquals(1, activeOther.size)
        assertEquals("s-other-1", activeOther[0].id)

        // Verify outbox
        val outboxOwner = syncOutboxDao.getPendingMutations(ownerUser)
        assertEquals(3, outboxOwner.size)
        val outboxOther = syncOutboxDao.getPendingMutations(otherUser)
        assertTrue(outboxOther.isEmpty())
    }

    @Test
    fun clearAllSolvesInScope_alreadyEmptyScope_returnsEmptyListWithoutErrors() = runTest {
        val cleared = repository.clearAllSolvesInScope(mode = Mode.MEGAMINX, ownerId = "empty-owner")
        assertTrue(cleared.isEmpty())

        val clearedAll = repository.clearAllSolvesInScope(mode = null, ownerId = "empty-owner")
        assertTrue(clearedAll.isEmpty())
    }

    @Test
    fun clearAllSolvesInScope_roundtripFidelity_restoreSolvesPreservesAllFieldsExact() = runTest {
        val ownerId = "roundtrip-owner"

        // Insert session entities first to ensure foreign keys pass
        val session1 = SessionEntity(
            id = "sess-roundtrip-1",
            ownerId = ownerId,
            name = "Session 3x3",
            event = "3x3",
            startedAt = "2026-09-11T12:00:00.000Z"
        )
        val session2 = SessionEntity(
            id = "sess-roundtrip-2",
            ownerId = ownerId,
            name = "Session 4x4",
            event = "4x4",
            startedAt = "2026-09-11T12:05:00.000Z"
        )
        sessionDao.insertAll(listOf(session1, session2))

        // Create 4 diverse solves:
        // Solve 1: Normal clean 3x3 solve
        val sClean = SolveTime(
            id = "s-roundtrip-clean",
            timeInMillis = 12345L,
            penalty = Penalty.NONE,
            timestamp = 1726056000000L,
            scramble = "R U R' U' F' U2 F",
            mode = Mode.CUBE_3x3,
            sessionId = "sess-roundtrip-1"
        )

        // Solve 2: +2 penalty solve
        val sPlusTwo = SolveTime(
            id = "s-roundtrip-plus2",
            timeInMillis = 14500L,
            penalty = Penalty.PLUS_TWO,
            timestamp = 1726056100000L,
            scramble = "D2 R' B2 L2 U2 F2 D2 R' F2 R2 U' L D' R2 B F R F'",
            mode = Mode.CUBE_3x3,
            sessionId = "sess-roundtrip-1"
        )

        // Solve 3: DNF penalty solve
        val sDnf = SolveTime(
            id = "s-roundtrip-dnf",
            timeInMillis = 45678L,
            penalty = Penalty.DNF,
            timestamp = 1726056200000L,
            scramble = "Rw2 Fw' U2 Fw2 Rw' Uw2 Bw2",
            mode = Mode.CUBE_4x4,
            sessionId = "sess-roundtrip-2"
        )

        // Solve 4: Complex scramble with quotes, commas, newlines, null sessionId
        val sComplex = SolveTime(
            id = "s-roundtrip-complex",
            timeInMillis = 8888L,
            penalty = Penalty.NONE,
            timestamp = 1726056300000L,
            scramble = "R U \"tricky, scramble\" \n F2 D'",
            mode = Mode.CUBE_2x2,
            sessionId = null
        )

        val originalSolves = listOf(sClean, sPlusTwo, sDnf, sComplex)
        for (s in originalSolves) {
            repository.saveSolve(s, ownerId = ownerId)
        }

        // Verify all 4 are saved and active
        assertEquals(4, repository.getAllActiveSolves(ownerId).size)

        // Clear all solves in scope
        val deletedSolves = repository.clearAllSolvesInScope(mode = null, ownerId = ownerId)
        assertEquals(4, deletedSolves.size)
        assertTrue("Active solves must be 0 after clear", repository.getAllActiveSolves(ownerId).isEmpty())

        // Clear outbox before restore to isolate mutations generated specifically by restoreSolves
        syncOutboxDao.clearOutbox(ownerId)

        // Execute Roundtrip: restore all deleted solves
        repository.restoreSolves(deletedSolves, ownerId = ownerId)

        val restoredSolves = repository.getAllActiveSolves(ownerId).associateBy { it.id }
        assertEquals(4, restoredSolves.size)

        // Verify exact fidelity for every solve and field
        for (original in originalSolves) {
            val restored = restoredSolves[original.id]
            assertNotNull("Solve ${original.id} must be present in restored list", restored)
            assertEquals("ID must match exactly", original.id, restored!!.id)
            assertEquals("timeInMillis must match exactly", original.timeInMillis, restored.timeInMillis)
            assertEquals("penalty must match exactly", original.penalty, restored.penalty)
            assertEquals("displayTime must match exactly", original.displayTime, restored.displayTime)
            assertEquals("timestamp (epoch millis) must match exactly", original.timestamp, restored.timestamp)
            assertEquals("scramble must match exactly", original.scramble, restored.scramble)
            assertEquals("mode must match exactly", original.mode, restored.mode)
            assertEquals("sessionId must match exactly", original.sessionId, restored.sessionId)
        }

        // Verify outbox has upsert mutations for restored solves
        val outbox = syncOutboxDao.getPendingMutations(ownerId)
        val upsertMutations = outbox.filter { it.action == "upsert" && it.entityType == "solve" }
        assertEquals(4, upsertMutations.size)
        assertEquals(originalSolves.map { it.id }.toSet(), upsertMutations.map { it.entityId }.toSet())
    }

    // =========================================================================
    // 3. getExistingSolveIds CHALLENGES
    // =========================================================================

    @Test
    fun getExistingSolveIds_activeSoftDeletedAndUninserted() = runTest {
        val sActive = SolveEntity(
            id = "solve-active-1",
            ownerId = "guest",
            event = "3x3",
            durationMs = 10000L,
            solvedAt = "2026-09-11T10:00:00.000Z",
            deletedAt = null
        )
        val sDeleted = SolveEntity(
            id = "solve-deleted-1",
            ownerId = "guest",
            event = "3x3",
            durationMs = 12000L,
            solvedAt = "2026-09-11T10:01:00.000Z",
            deletedAt = "2026-09-11T10:05:00.000Z"
        )
        solveDao.insertAll(listOf(sActive, sDeleted))

        // 1. Query with active, soft-deleted, and uninserted IDs
        val queryIds = listOf("solve-active-1", "solve-deleted-1", "solve-uninserted-1", "solve-uninserted-2")
        val existing = solveDao.getExistingSolveIds(queryIds)

        // Both active and soft-deleted must be returned (critical for CSV deduplication)
        assertEquals(2, existing.size)
        assertTrue(existing.contains("solve-active-1"))
        assertTrue(existing.contains("solve-deleted-1"))
        assertFalse(existing.contains("solve-uninserted-1"))
        assertFalse(existing.contains("solve-uninserted-2"))

        // 2. Query only soft-deleted
        val onlyDeleted = solveDao.getExistingSolveIds(listOf("solve-deleted-1"))
        assertEquals(1, onlyDeleted.size)
        assertEquals("solve-deleted-1", onlyDeleted[0])

        // 3. Query only uninserted
        val onlyUninserted = solveDao.getExistingSolveIds(listOf("solve-uninserted-1"))
        assertTrue(onlyUninserted.isEmpty())

        // 4. Query empty list
        val emptyResult = solveDao.getExistingSolveIds(emptyList())
        assertTrue(emptyResult.isEmpty())

        // 5. Query with duplicate entries of existing ID
        val dupsResult = solveDao.getExistingSolveIds(listOf("solve-active-1", "solve-active-1", "solve-active-1"))
        assertEquals(1, dupsResult.size)
        assertEquals("solve-active-1", dupsResult[0])
    }

    @Test
    fun getExistingSolveIds_handlesLargeBatchOfIdsWithoutSqliteError() = runTest {
        // Test chunked / multi-item queries
        val entities = (1..50).map { i ->
            SolveEntity(
                id = "solve-batch-$i",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L + i,
                solvedAt = "2026-09-11T10:00:00.000Z",
                deletedAt = if (i % 2 == 0) "2026-09-11T10:10:00.000Z" else null
            )
        }
        solveDao.insertAll(entities)

        // Query all 50 + 20 uninserted
        val candidateIds = (1..70).map { "solve-batch-$it" }
        val found = solveDao.getExistingSolveIds(candidateIds)

        assertEquals(50, found.size)
        assertEquals(entities.map { it.id }.toSet(), found.toSet())
    }
}
