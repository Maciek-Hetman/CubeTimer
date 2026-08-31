package com.maciekhetman.cubetimer.model

import java.util.UUID

/**
 * Domain model representing a speedcubing session.
 */
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val ownerId: String = "guest",
    val name: String,
    val event: Mode = Mode.CUBE_3x3,
    val kind: SessionKind = SessionKind.AUTOMATIC,
    val startedAt: String, // ISO 8601 UTC string
    val endedAt: String? = null,
    val archived: Boolean = false,
    val version: Long = 0L,
    val updatedAt: String = startedAt,
    val deletedAt: String? = null
) {
    val isOpen: Boolean
        get() = endedAt == null && !archived && deletedAt == null

    val isDeleted: Boolean
        get() = deletedAt != null
}
