package com.maciekhetman.cubetimer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sync_conflicts",
    indices = [
        Index(value = ["owner_id", "resolved", "created_at"], name = "idx_conflicts_owner_resolved_created_at"),
        Index(value = ["owner_id", "entity_id"], name = "idx_conflicts_owner_entity_id")
    ]
)
data class ConflictEntity(
    @PrimaryKey
    @ColumnInfo(name = "conflict_id")
    val conflictId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "owner_id")
    val ownerId: String,

    @ColumnInfo(name = "mutation_id")
    val mutationId: String,

    @ColumnInfo(name = "entity_type")
    val entityType: String, // "solve" or "session"

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "server_version")
    val serverVersion: Long = 0L,

    @ColumnInfo(name = "server_updated_at")
    val serverUpdatedAt: String? = null,

    @ColumnInfo(name = "local_payload_json")
    val localPayloadJson: String? = null,

    @ColumnInfo(name = "server_payload_json")
    val serverPayloadJson: String? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String = "Conflict detected: server version mismatch",

    @ColumnInfo(name = "created_at")
    val createdAt: String, // ISO 8601 UTC timestamp

    @ColumnInfo(name = "resolved")
    val resolved: Boolean = false,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: String? = null
)

val ConflictEntity.id: String get() = conflictId
val ConflictEntity.entity: String get() = entityType
