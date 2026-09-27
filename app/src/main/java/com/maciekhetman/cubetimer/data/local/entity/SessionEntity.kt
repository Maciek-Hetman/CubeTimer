package com.maciekhetman.cubetimer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sessions",
    indices = [
        Index(value = ["owner_id", "event", "updated_at"], name = "idx_sessions_owner_event_updated_at"),
        Index(value = ["owner_id", "event", "started_at"], name = "idx_sessions_owner_event_started_at"),
        Index(value = ["owner_id", "kind", "ended_at"], name = "idx_sessions_owner_kind_ended_at"),
        Index(value = ["owner_id", "archived"], name = "idx_sessions_owner_archived"),
        Index(value = ["deleted_at"], name = "idx_sessions_deleted_at")
    ]
)
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "owner_id")
    val ownerId: String = "guest",

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "event")
    val event: String = "3x3",

    @ColumnInfo(name = "kind")
    val kind: String = "automatic", // "automatic" or "manual"

    @ColumnInfo(name = "started_at")
    val startedAt: String, // ISO 8601 UTC string

    @ColumnInfo(name = "ended_at")
    val endedAt: String? = null,

    @ColumnInfo(name = "archived")
    val archived: Boolean = false,

    @ColumnInfo(name = "version")
    val version: Long = 0L,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String = startedAt,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null
)

val SessionEntity.isOpen: Boolean
    get() = endedAt == null && !archived && deletedAt == null
