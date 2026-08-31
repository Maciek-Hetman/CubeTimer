package com.maciekhetman.cubetimer.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    // --- Reactive Flow Queries ---

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL AND archived = 0 
        ORDER BY started_at DESC
    """)
    fun observeSessionsByEvent(ownerId: String, event: String): Flow<List<SessionEntity>>

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL AND archived = 0 
        ORDER BY started_at DESC
    """)
    fun observeActiveSessionsByEvent(ownerId: String, event: String): Flow<List<SessionEntity>>

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
        ORDER BY started_at DESC
    """)
    fun observeAllSessionsByEvent(ownerId: String, event: String): Flow<List<SessionEntity>>

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND deleted_at IS NULL 
        ORDER BY started_at DESC
    """)
    fun observeAllSessions(ownerId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun observeSessionById(id: String): Flow<SessionEntity?>

    // --- One-Shot Queries ---

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getSessionById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id IN (:ids)")
    suspend fun getSessionsByIds(ids: List<String>): List<SessionEntity>

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND kind = 'automatic' 
          AND ended_at IS NULL AND deleted_at IS NULL AND archived = 0 
        ORDER BY started_at DESC 
        LIMIT 1
    """)
    suspend fun getOpenAutomaticSession(ownerId: String, event: String): SessionEntity?

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL AND archived = 0 
        ORDER BY started_at DESC 
        LIMIT 1
    """)
    suspend fun getActiveSession(ownerId: String, event: String): SessionEntity?

    @Query("""
        SELECT * FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL AND archived = 0 
        ORDER BY started_at DESC 
        LIMIT 1
    """)
    suspend fun getMostRecentActiveSession(ownerId: String, event: String): SessionEntity?

    @Query("""
        SELECT name FROM sessions 
        WHERE owner_id = :ownerId AND event = :event AND deleted_at IS NULL 
          AND name LIKE :namePrefix || '%'
    """)
    suspend fun getSessionNamesWithPrefix(ownerId: String, event: String, namePrefix: String): List<String>

    @Query("SELECT * FROM sessions WHERE owner_id = :ownerId AND deleted_at IS NULL")
    suspend fun getAllActiveSessionsForOwner(ownerId: String): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE owner_id = :ownerId")
    suspend fun getAllSessionsForOwner(ownerId: String): List<SessionEntity>

    // --- Insert / Update / Upsert Operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<SessionEntity>): List<Long>

    @Update
    suspend fun update(session: SessionEntity): Int

    @Upsert
    suspend fun upsert(session: SessionEntity): Long

    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>): List<Long>

    // --- Lifecycle State Updates ---

    @Query("""
        UPDATE sessions 
        SET ended_at = :endedAt, updated_at = :updatedAt 
        WHERE id = :id AND ended_at IS NULL
    """)
    suspend fun closeSession(id: String, endedAt: String, updatedAt: String): Int

    @Query("""
        UPDATE sessions 
        SET name = :newName, updated_at = :updatedAt 
        WHERE id = :id
    """)
    suspend fun renameSession(id: String, newName: String, updatedAt: String): Int

    @Query("""
        UPDATE sessions 
        SET archived = :archived, updated_at = :updatedAt 
        WHERE id = :id
    """)
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: String): Int

    @Query("UPDATE sessions SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: String, updatedAt: String): Int

    @Delete
    suspend fun delete(session: SessionEntity): Int

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM sessions WHERE owner_id = :ownerId")
    suspend fun deleteSessionsForOwner(ownerId: String): Int

    // --- Guest Adoption ---

    @Query("""
        UPDATE sessions 
        SET owner_id = :targetOwnerId, version = 0, updated_at = :updatedAt 
        WHERE owner_id = :guestOwnerId
    """)
    suspend fun adoptGuestSessions(guestOwnerId: String, targetOwnerId: String, updatedAt: String): Int
}
