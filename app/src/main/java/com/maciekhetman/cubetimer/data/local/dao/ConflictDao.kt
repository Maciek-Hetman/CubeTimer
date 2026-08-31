package com.maciekhetman.cubetimer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConflictDao {

    @Query("""
        SELECT * FROM sync_conflicts 
        WHERE owner_id = :ownerId AND resolved = 0 
        ORDER BY created_at DESC
    """)
    fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>>

    @Query("""
        SELECT * FROM sync_conflicts 
        WHERE owner_id = :ownerId 
        ORDER BY created_at DESC
    """)
    fun observeAllConflicts(ownerId: String): Flow<List<ConflictEntity>>

    @Query("""
        SELECT * FROM sync_conflicts 
        WHERE owner_id = :ownerId 
        ORDER BY created_at DESC
    """)
    suspend fun getAll(ownerId: String): List<ConflictEntity>

    @Query("SELECT * FROM sync_conflicts WHERE conflict_id = :id LIMIT 1")
    suspend fun getConflictById(id: String): ConflictEntity?

    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE owner_id = :ownerId AND resolved = 0")
    fun observeUnresolvedCount(ownerId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conflict: ConflictEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(conflicts: List<ConflictEntity>): List<Long>

    @Update
    suspend fun update(conflict: ConflictEntity): Int

    @Query("""
        UPDATE sync_conflicts 
        SET resolved = 1, resolved_at = :resolvedAt 
        WHERE conflict_id = :conflictId
    """)
    suspend fun resolveConflict(conflictId: String, resolvedAt: String): Int

    @Query("""
        UPDATE sync_conflicts 
        SET resolved = 1, resolved_at = :resolvedAt 
        WHERE conflict_id = :id
    """)
    suspend fun markResolved(id: String, resolvedAt: String): Int

    @Query("DELETE FROM sync_conflicts WHERE conflict_id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM sync_conflicts WHERE owner_id = :ownerId")
    suspend fun deleteForOwner(ownerId: String): Int
}
