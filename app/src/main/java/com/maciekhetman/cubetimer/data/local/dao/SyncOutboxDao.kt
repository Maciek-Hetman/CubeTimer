package com.maciekhetman.cubetimer.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {

    /**
     * Mutations ready to send: everything except rows currently `in_flight` and rows that were
     * dead-lettered (`dead`, see [markDead]). This includes both `pending` rows and
     * previously-`failed` rows, which are retried on the next sync.
     *
     * Ordered by `client_time` with a `rowid` tiebreak: mutations enqueued together in the same
     * write transaction (e.g. a session create + its first solve) share the same client_time, so
     * the tiebreak keeps them in enqueue order - which matters because sessions must reach the
     * server before solves that reference them.
     */
    @Query("""
        SELECT * FROM sync_outbox
        WHERE owner_id = :ownerId AND status NOT IN ('in_flight', 'dead')
        ORDER BY client_time ASC, rowid ASC
        LIMIT :limit
    """)
    suspend fun getPendingMutations(ownerId: String, limit: Int): List<SyncOutboxEntity>

    /** [getPendingMutations] with the sync protocol's default batch size (500). */
    suspend fun getPendingMutations(ownerId: String): List<SyncOutboxEntity> =
        getPendingMutations(ownerId, 500)

    /** All outbox rows for an owner regardless of status (pending, in_flight, failed or dead). */
    @Query("""
        SELECT * FROM sync_outbox
        WHERE owner_id = :ownerId
        ORDER BY client_time ASC, rowid ASC
        LIMIT :limit
    """)
    suspend fun getAllPendingForOwner(ownerId: String, limit: Int): List<SyncOutboxEntity>

    /** [getAllPendingForOwner] with the sync protocol's default batch size (500). */
    suspend fun getAllPendingForOwner(ownerId: String): List<SyncOutboxEntity> =
        getAllPendingForOwner(ownerId, 500)

    @Query("SELECT * FROM sync_outbox WHERE id = :id LIMIT 1")
    suspend fun getMutationById(id: String): SyncOutboxEntity?

    /**
     * The newest live mutation queued for the entity (pending, in_flight or failed). Dead rows are
     * skipped: they will never be sent, so they are not a "newer local edit" to rebase.
     */
    @Query("""
        SELECT * FROM sync_outbox
        WHERE owner_id = :ownerId AND entity_type = :entityType AND entity_id = :entityId
          AND status != 'dead'
        ORDER BY client_time DESC, rowid DESC
        LIMIT 1
    """)
    suspend fun getPendingMutationForEntity(ownerId: String, entityType: String, entityId: String): SyncOutboxEntity?

    /** Live outbox rows for the owner (pending, in_flight, failed); dead rows never upload, so aren't "pending". */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId AND status != 'dead'")
    fun observePendingCount(ownerId: String): Flow<Int>

    /** Counts the live outbox rows for the owner (pending, in_flight, failed), i.e. everything except `dead`. */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId AND status != 'dead'")
    suspend fun countPending(ownerId: String): Int

    /**
     * Every live outbox entity for [ownerId], so a sync page can test "is this entity protected?"
     * in memory instead of one indexed lookup per incoming change.
     *
     * Dead rows are left out. A dead mutation's edit is never going to reach the server: keeping
     * its entity "protected" would freeze it against every other device's changes forever. So once
     * a mutation is dead, the server's copy wins over it.
     */
    @Query("""
        SELECT entity_type, entity_id FROM sync_outbox
        WHERE owner_id = :ownerId AND status != 'dead'
    """)
    suspend fun getLiveEntityKeys(ownerId: String): List<OutboxEntityKey>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(mutation: SyncOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueAll(mutations: List<SyncOutboxEntity>): List<Long>

    @Update
    suspend fun update(mutation: SyncOutboxEntity): Int

    @Query("UPDATE sync_outbox SET status = 'in_flight', last_attempt_at = :attemptAt WHERE id IN (:ids)")
    suspend fun markInFlight(ids: List<String>, attemptAt: Long): Int

    @Query("UPDATE sync_outbox SET status = 'pending' WHERE id IN (:ids)")
    suspend fun resetInFlight(ids: List<String>): Int

    @Query("UPDATE sync_outbox SET status = 'pending' WHERE owner_id = :ownerId AND status = 'in_flight'")
    suspend fun resetAllInFlight(ownerId: String): Int

    /**
     * Marks the rows `failed` (retried on the next sync), counting the attempt. At most
     * [MAX_IN_LIST_SIZE] ids per call; use [markAllFailedChunked] for arbitrary lists.
     */
    @Query("""
        UPDATE sync_outbox
        SET status = 'failed', attempt_count = attempt_count + 1, last_attempt_at = :attemptAt, last_error = :error
        WHERE id IN (:ids)
    """)
    suspend fun markAllFailed(ids: List<String>, error: String?, attemptAt: Long): Int

    /**
     * Dead-letters a mutation the server permanently rejected on its own (a request-level 4xx that
     * retrying can never fix). A `dead` row is kept for diagnostics (`last_error`) but is excluded
     * from [getPendingMutations], [countPending], [observePendingCount], [getLiveEntityKeys]
     * and [getPendingMutationForEntity], so it is never sent again and blocks nothing.
     */
    @Query("""
        UPDATE sync_outbox
        SET status = 'dead', attempt_count = attempt_count + 1, last_attempt_at = :attemptAt, last_error = :error
        WHERE id = :id
    """)
    suspend fun markDead(id: String, error: String?, attemptAt: Long): Int

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteMutations(ids: List<String>): Int

    @Query("DELETE FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun clearOutbox(ownerId: String): Int
}

// Chunked variants of the bulk status updates above. Extension functions (not DAO default methods)
// so they dispatch through the receiver's own overrides, like the helpers in ChunkedQueries.kt.

/** One live outbox row's entity, as returned by [SyncOutboxDao.getLiveEntityKeys]. */
data class OutboxEntityKey(
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String
)

/** [SyncOutboxDao.markInFlight] split into batches that stay under SQLite's bind-variable limit. */
suspend fun SyncOutboxDao.markInFlightChunked(ids: List<String>, attemptAt: Long): Int =
    ids.chunked(MAX_IN_LIST_SIZE).sumOf { markInFlight(it, attemptAt) }

/** [SyncOutboxDao.resetInFlight] split into batches that stay under SQLite's bind-variable limit. */
suspend fun SyncOutboxDao.resetInFlightChunked(ids: List<String>): Int =
    ids.chunked(MAX_IN_LIST_SIZE).sumOf { resetInFlight(it) }

/** [SyncOutboxDao.markAllFailed] split into batches that stay under SQLite's bind-variable limit. */
suspend fun SyncOutboxDao.markAllFailedChunked(ids: List<String>, error: String?, attemptAt: Long): Int =
    ids.chunked(MAX_IN_LIST_SIZE).sumOf { markAllFailed(it, error, attemptAt) }
