package com.maciekhetman.cubetimer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["status", "client_time"], name = "idx_outbox_status_client_time"),
        Index(value = ["entity_type", "entity_id"], name = "idx_outbox_entity_type_entity_id"),
        Index(value = ["owner_id", "client_time"], name = "idx_outbox_owner_client_time"),
        Index(value = ["owner_id", "entity_type", "entity_id"], name = "idx_outbox_owner_entity_entity_id")
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "owner_id")
    val ownerId: String,

    @ColumnInfo(name = "entity_type")
    val entityType: String, // "solve" or "session"

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "action")
    val action: String, // "create", "update", "upsert", "delete"

    @ColumnInfo(name = "base_version")
    val baseVersion: Long = 0L,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String? = null,

    @ColumnInfo(name = "client_time")
    val clientTime: String, // ISO 8601 UTC timestamp

    @ColumnInfo(name = "status")
    val status: String = "pending", // "pending", "in_flight", "failed"

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null
)

val SyncOutboxEntity.entity: String get() = entityType
val SyncOutboxEntity.operation: String get() = action
val SyncOutboxEntity.createdAt: String get() = clientTime
val SyncOutboxEntity.mutationId: String get() = id
