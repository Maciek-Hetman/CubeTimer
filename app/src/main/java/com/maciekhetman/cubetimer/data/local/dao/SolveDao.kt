package com.maciekhetman.cubetimer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SolveDao {

    // --- Reactive Flow Queries ---

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
        ORDER BY solved_at ASC
    """)
    fun observeSolvesByEvent(ownerId: String, event: String): Flow<List<SolveEntity>>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
        ORDER BY solved_at DESC
    """)
    fun observeSolvesByEventDesc(ownerId: String, event: String): Flow<List<SolveEntity>>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL 
        ORDER BY solved_at ASC
    """)
    fun observeSolvesBySession(ownerId: String, sessionId: String): Flow<List<SolveEntity>>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL 
        ORDER BY solved_at DESC
    """)
    fun observeSolvesBySessionDesc(ownerId: String, sessionId: String): Flow<List<SolveEntity>>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND deleted_at IS NULL 
        ORDER BY solved_at ASC
    """)
    fun observeAllSolves(ownerId: String): Flow<List<SolveEntity>>

    @Query("""
        SELECT COUNT(*) FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL
    """)
    fun observeSolveCount(ownerId: String, event: String): Flow<Int>

    // --- One-Shot Queries ---

    @Query("SELECT * FROM solves WHERE id = :id LIMIT 1")
    suspend fun getSolveById(id: String): SolveEntity?

    @Query("SELECT * FROM solves WHERE id IN (:ids)")
    suspend fun getSolvesByIds(ids: List<String>): List<SolveEntity>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
        ORDER BY solved_at ASC
    """)
    suspend fun getSolvesByEvent(ownerId: String, event: String): List<SolveEntity>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL 
        ORDER BY solved_at ASC
    """)
    suspend fun getSolvesBySession(ownerId: String, sessionId: String): List<SolveEntity>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL 
        ORDER BY solved_at DESC 
        LIMIT 1
    """)
    suspend fun getLastSolveForSession(ownerId: String, sessionId: String): SolveEntity?

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
        ORDER BY solved_at DESC 
        LIMIT 1
    """)
    suspend fun getLastSolveForEvent(ownerId: String, event: String): SolveEntity?

    @Query("SELECT * FROM solves WHERE owner_id = :ownerId AND deleted_at IS NULL")
    suspend fun getAllActiveSolvesForOwner(ownerId: String): List<SolveEntity>

    @Query("SELECT * FROM solves WHERE owner_id = :ownerId")
    suspend fun getAllSolvesForOwner(ownerId: String): List<SolveEntity>

    // --- Insert / Update / Upsert Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(solve: SolveEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(solves: List<SolveEntity>): List<Long>

    @Update
    suspend fun update(solve: SolveEntity): Int

    @Upsert
    suspend fun upsert(solve: SolveEntity): Long

    @Upsert
    suspend fun upsertAll(solves: List<SolveEntity>): List<Long>

    // --- Soft Delete / Hard Delete Operations ---

    @Query("UPDATE solves SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String, updatedAt: String): Int

    @Query("UPDATE solves SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id IN (:ids)")
    suspend fun softDeleteAll(ids: List<String>, deletedAt: String, updatedAt: String): Int

    @Delete
    suspend fun delete(solve: SolveEntity): Int

    @Query("DELETE FROM solves WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM solves WHERE owner_id = :ownerId")
    suspend fun deleteSolvesForOwner(ownerId: String): Int

    // --- Guest Adoption & Bulk Updates ---

    @Query("""
        UPDATE solves 
        SET owner_id = :targetOwnerId, version = 0, updated_at = :updatedAt 
        WHERE owner_id = :guestOwnerId
    """)
    suspend fun adoptGuestSolves(guestOwnerId: String, targetOwnerId: String, updatedAt: String): Int

    @Query("""
        UPDATE solves 
        SET session_id = :newSessionId, updated_at = :updatedAt 
        WHERE session_id = :oldSessionId AND owner_id = :ownerId
    """)
    suspend fun reassignSolvesSession(ownerId: String, oldSessionId: String, newSessionId: String, updatedAt: String): Int
}
