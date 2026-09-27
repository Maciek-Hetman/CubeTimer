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
    fun observeSolveCountByEvent(ownerId: String, event: String): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL
    """)
    fun observeSolveCountBySession(ownerId: String, sessionId: String): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM solves 
        WHERE owner_id = :ownerId AND deleted_at IS NULL
    """)
    fun observeAllSolvesCount(ownerId: String): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL
    """)
    suspend fun getSolveCountByEvent(ownerId: String, event: String): Int

    @Query("""
        SELECT COUNT(*) FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL
    """)
    suspend fun getSolveCountBySession(ownerId: String, sessionId: String): Int

    // --- Chunked Paged Queries ---

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
        ORDER BY solved_at DESC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSolvesPagedByEvent(
        ownerId: String,
        event: String,
        limit: Int,
        offset: Int
    ): List<SolveEntity>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND session_id = :sessionId AND deleted_at IS NULL 
        ORDER BY solved_at DESC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getSolvesPagedBySession(
        ownerId: String,
        sessionId: String,
        limit: Int,
        offset: Int
    ): List<SolveEntity>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId AND deleted_at IS NULL 
        ORDER BY solved_at DESC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getAllSolvesPaged(
        ownerId: String,
        limit: Int,
        offset: Int
    ): List<SolveEntity>

    // --- Prior Best Solve Lookup (Historical PB Calculation) ---

    @Query("""
        SELECT MIN(CASE WHEN penalty = 'plus_two' THEN duration_ms + 2000 ELSE duration_ms END)
        FROM solves 
        WHERE owner_id = :ownerId 
          AND event = :event 
          AND (:excludeSolveId IS NULL OR id != :excludeSolveId)
          AND solved_at < :solvedAt 
          AND deleted_at IS NULL 
          AND penalty != 'dnf'
    """)
    suspend fun getPriorBestSolveDuration(
        ownerId: String,
        event: String,
        solvedAt: String,
        excludeSolveId: String? = null
    ): Long?

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId 
          AND event = :event 
          AND (:excludeSolveId IS NULL OR id != :excludeSolveId)
          AND solved_at < :solvedAt 
          AND deleted_at IS NULL 
          AND penalty != 'dnf'
        ORDER BY (CASE WHEN penalty = 'plus_two' THEN duration_ms + 2000 ELSE duration_ms END) ASC 
        LIMIT 1
    """)
    suspend fun getPriorBestSolve(
        ownerId: String,
        event: String,
        solvedAt: String,
        excludeSolveId: String? = null
    ): SolveEntity?

    // --- One-Shot Queries ---

    @Query("SELECT id FROM solves WHERE id IN (:ids)")
    suspend fun getExistingSolveIds(ids: List<String>): List<String>

    @Query("""
        SELECT * FROM solves 
        WHERE owner_id = :ownerId 
          AND (:sessionId IS NULL OR session_id = :sessionId) 
          AND (:event IS NULL OR event = :event) 
          AND deleted_at IS NULL 
        ORDER BY solved_at ASC
    """)
    suspend fun getSolvesByScope(
        ownerId: String,
        sessionId: String? = null,
        event: String? = null
    ): List<SolveEntity>

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
}
