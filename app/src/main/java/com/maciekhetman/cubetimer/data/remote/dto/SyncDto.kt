package com.maciekhetman.cubetimer.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DeviceDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("platform")
    val platform: String = "android"
)

@Serializable
data class SyncMutationDto(
    @SerialName("id")
    val id: String,
    @SerialName("entity")
    val entity: String, // "session" or "solve"
    @SerialName("entity_id")
    val entityId: String,
    @SerialName("operation")
    val operation: String, // "upsert" or "delete"
    @SerialName("base_version")
    val baseVersion: Long = 0L,
    @SerialName("data")
    val data: JsonElement? = null
)

@Serializable
data class SyncRequest(
    @SerialName("cursor")
    val cursor: Long = 0L,
    @SerialName("device")
    val device: DeviceDto,
    @SerialName("mutations")
    val mutations: List<SyncMutationDto> = emptyList(),
    @SerialName("limit")
    val limit: Int? = 500
)

@Serializable
data class MutationOutcomeDto(
    @SerialName("mutation_id")
    val mutationId: String,
    @SerialName("status")
    val status: String, // "accepted", "rejected", "conflict"
    @SerialName("version")
    val version: Long? = null,
    @SerialName("code")
    val code: String? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("current")
    val current: JsonElement? = null
)

@Serializable
data class ChangeDto(
    @SerialName("cursor")
    val cursor: Long = 0L,
    @SerialName("entity")
    val entity: String, // "session" or "solve"
    @SerialName("entity_id")
    val entityId: String,
    @SerialName("operation")
    val operation: String, // "upsert" or "delete"
    @SerialName("version")
    val version: Long = 0L,
    @SerialName("data")
    val data: JsonElement? = null,
    @SerialName("changed_at")
    val changedAt: String? = null
)

@Serializable
data class SyncResponse(
    @SerialName("outcomes")
    val outcomes: List<MutationOutcomeDto> = emptyList(),
    @SerialName("changes")
    val changes: List<ChangeDto> = emptyList(),
    @SerialName("next_cursor")
    val nextCursor: Long = 0L,
    @SerialName("has_more")
    val hasMore: Boolean = false
)

@Serializable
data class SnapshotRequest(
    @SerialName("device")
    val device: DeviceDto,
    @SerialName("cursor")
    val cursor: Long = 0L,
    @SerialName("after_id")
    val afterId: String = "00000000-0000-0000-0000-000000000000",
    @SerialName("entity")
    val entity: String = "session", // "session" or "solve"
    @SerialName("page_size")
    val pageSize: Int? = 500
)

@Serializable
data class SessionSnapshotDto(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("event")
    val event: String,
    @SerialName("kind")
    val kind: String = "automatic",
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("ended_at")
    val endedAt: String? = null,
    @SerialName("archived")
    val archived: Boolean = false,
    @SerialName("version")
    val version: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null
)

@Serializable
data class SolveSnapshotDto(
    @SerialName("id")
    val id: String,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("penalty")
    val penalty: String = "none",
    @SerialName("solved_at")
    val solvedAt: String,
    @SerialName("scramble")
    val scramble: String = "",
    @SerialName("event")
    val event: String = "3x3",
    @SerialName("version")
    val version: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null
)

@Serializable
data class SnapshotResponse(
    @SerialName("sessions")
    val sessions: List<SessionSnapshotDto>? = null,
    @SerialName("solves")
    val solves: List<SolveSnapshotDto>? = null,
    @SerialName("cursor")
    val cursor: Long = 0L,
    @SerialName("has_more")
    val hasMore: Boolean = false,
    @SerialName("next_entity")
    val nextEntity: String? = null,
    @SerialName("next_after_id")
    val nextAfterId: String? = null
)

@Serializable
data class DeleteStubDto(
    @SerialName("id")
    val id: String,
    @SerialName("version")
    val version: Long = 0L,
    @SerialName("deleted_at")
    val deletedAt: String? = null
)

@Serializable
data class ConflictStubDto(
    @SerialName("id")
    val id: String,
    @SerialName("version")
    val version: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: String? = null
)
