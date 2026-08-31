package com.maciekhetman.cubetimer.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload serialized for session mutations in sync outbox and sync engine.
 */
@Serializable
data class SessionSyncPayload(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("event")
    val event: String,
    @SerialName("kind")
    val kind: String,
    @SerialName("started_at")
    val startedAt: String,
    @SerialName("ended_at")
    val endedAt: String? = null,
    @SerialName("archived")
    val archived: Boolean = false
)

/**
 * Payload serialized for solve mutations in sync outbox and sync engine.
 */
@Serializable
data class SolveSyncPayload(
    @SerialName("id")
    val id: String,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("penalty")
    val penalty: String,
    @SerialName("solved_at")
    val solvedAt: String,
    @SerialName("scramble")
    val scramble: String,
    @SerialName("event")
    val event: String
)
