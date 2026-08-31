package com.maciekhetman.cubetimer.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "solves",
    indices = [
        Index(value = ["owner_id", "event", "solved_at"], name = "idx_solves_owner_event_solved_at"),
        Index(value = ["owner_id", "session_id", "solved_at"], name = "idx_solves_owner_session_solved_at"),
        Index(value = ["owner_id", "solved_at"], name = "idx_solves_owner_solved_at"),
        Index(value = ["session_id"], name = "idx_solves_session_id"),
        Index(value = ["deleted_at"], name = "idx_solves_deleted_at")
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.SET_NULL,
            deferred = true
        )
    ]
)
data class SolveEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "owner_id")
    val ownerId: String = "guest",

    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    @ColumnInfo(name = "event")
    val event: String = "3x3",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "penalty")
    val penalty: String = "none",

    @ColumnInfo(name = "solved_at")
    val solvedAt: String,

    @ColumnInfo(name = "scramble")
    val scramble: String = "",

    @ColumnInfo(name = "version")
    val version: Long = 0L,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String = solvedAt,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null
)

val SolveEntity.displayDurationMs: Long
    get() = when (penalty) {
        "none" -> durationMs
        "plus_two" -> durationMs + 2000L
        "dnf" -> durationMs
        else -> durationMs
    }

val SolveEntity.isDnf: Boolean
    get() = penalty == "dnf"

val SolveEntity.isPlusTwo: Boolean
    get() = penalty == "plus_two"
