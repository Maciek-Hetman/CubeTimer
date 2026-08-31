package com.maciekhetman.cubetimer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetadataDao {

    @Query("SELECT * FROM sync_metadata WHERE owner_id = :ownerId LIMIT 1")
    suspend fun getMetadata(ownerId: String): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE owner_id = :ownerId LIMIT 1")
    fun observeMetadata(ownerId: String): Flow<SyncMetadataEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: SyncMetadataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: SyncMetadataEntity): Long

    @Query("""
        UPDATE sync_metadata 
        SET cursor = :cursor, last_sync_time = :lastSyncTime, last_error = NULL 
        WHERE owner_id = :ownerId
    """)
    suspend fun updateCursor(ownerId: String, cursor: Long, lastSyncTime: String): Int

    @Query("""
        UPDATE sync_metadata 
        SET last_sync_time = :lastSyncTime 
        WHERE owner_id = :ownerId
    """)
    suspend fun updateLastSyncTime(ownerId: String, lastSyncTime: String): Int

    @Query("""
        UPDATE sync_metadata 
        SET is_syncing = :isSyncing 
        WHERE owner_id = :ownerId
    """)
    suspend fun setSyncing(ownerId: String, isSyncing: Boolean): Int

    @Query("""
        UPDATE sync_metadata 
        SET last_error = :error, is_syncing = 0 
        WHERE owner_id = :ownerId
    """)
    suspend fun setSyncError(ownerId: String, error: String?): Int

    @Query("DELETE FROM sync_metadata WHERE owner_id = :ownerId")
    suspend fun deleteForOwner(ownerId: String): Int
}
