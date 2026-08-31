package com.maciekhetman.cubetimer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {

    @Query("""
        SELECT * FROM sync_outbox 
        WHERE owner_id = :ownerId AND status != 'in_flight' 
        ORDER BY client_time ASC 
        LIMIT :limit
    """)
    suspend fun getPendingMutations(ownerId: String, limit: Int): List<SyncOutboxEntity>

    @Query("""
        SELECT * FROM sync_outbox 
        WHERE owner_id = :ownerId AND status != 'in_flight' 
        ORDER BY client_time ASC 
        LIMIT 500
    """)
    suspend fun getPendingMutations(ownerId: String): List<SyncOutboxEntity>

    @Query("""
        SELECT * FROM sync_outbox 
        WHERE owner_id = :ownerId 
        ORDER BY client_time ASC 
        LIMIT :limit
    """)
    suspend fun getAllPendingForOwner(ownerId: String, limit: Int): List<SyncOutboxEntity>

    @Query("""
        SELECT * FROM sync_outbox 
        WHERE owner_id = :ownerId 
        ORDER BY client_time ASC 
        LIMIT 500
    """)
    suspend fun getAllPendingForOwner(ownerId: String): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE id = :id LIMIT 1")
    suspend fun getMutationById(id: String): SyncOutboxEntity?

    @Query("""
        SELECT * FROM sync_outbox 
        WHERE owner_id = :ownerId AND entity_type = :entityType AND entity_id = :entityId 
        ORDER BY client_time DESC 
        LIMIT 1
    """)
    suspend fun getPendingMutationForEntity(ownerId: String, entityType: String, entityId: String): SyncOutboxEntity?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId")
    fun observePendingCount(ownerId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun countPending(ownerId: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId AND entity_id = :entityId")
    suspend fun countPendingForEntity(ownerId: String, entityId: String): Int

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

    @Query("""
        UPDATE sync_outbox 
        SET status = 'failed', attempt_count = attempt_count + 1, last_attempt_at = :attemptAt, last_error = :error 
        WHERE id = :id
    """)
    suspend fun markFailed(id: String, error: String?, attemptAt: Long): Int

    @Query("""
        UPDATE sync_outbox 
        SET attempt_count = attempt_count + 1, last_attempt_at = :attemptAt, last_error = :error 
        WHERE id = :id
    """)
    suspend fun recordAttempt(id: String, attemptAt: Long, error: String?): Int

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteMutations(ids: List<String>): Int

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun clearOutbox(ownerId: String): Int

    @Query("DELETE FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun deleteForOwner(ownerId: String): Int
}
