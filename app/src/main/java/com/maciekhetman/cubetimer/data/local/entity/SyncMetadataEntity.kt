package com.maciekhetman.cubetimer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_metadata"
)
data class SyncMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "owner_id")
    val ownerId: String, // User ID UUID or "guest"

    @ColumnInfo(name = "cursor")
    val cursor: Long = 0L,

    @ColumnInfo(name = "last_sync_time")
    val lastSyncTime: String? = null,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "device_name")
    val deviceName: String = "Android Device",

    @ColumnInfo(name = "device_platform")
    val devicePlatform: String = "android",

    @ColumnInfo(name = "is_syncing")
    val isSyncing: Boolean = false,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null
)
