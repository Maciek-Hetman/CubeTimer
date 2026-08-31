package com.maciekhetman.cubetimer.data.local.mapper

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime

import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload

fun SolveEntity.toSolveTime(): SolveTime {
    val epochMillis = CubeTypeConverters.isoToEpochMillis(this.solvedAt)
    return SolveTime(
        id = this.id,
        timeInMillis = this.durationMs,
        penalty = CubeTypeConverters.toPenalty(this.penalty),
        timestamp = epochMillis,
        scramble = this.scramble,
        mode = CubeTypeConverters.toMode(this.event),
        sessionId = this.sessionId
    )
}

fun SolveTime.toSolveEntity(
    ownerId: String = "guest",
    sessionId: String? = this.sessionId,
    version: Long = 0L,
    deletedAt: String? = null
): SolveEntity {
    val iso = CubeTypeConverters.epochMillisToIso(this.timestamp)
    return SolveEntity(
        id = this.id,
        ownerId = ownerId,
        sessionId = sessionId ?: this.sessionId,
        event = CubeTypeConverters.fromMode(this.mode),
        durationMs = this.timeInMillis,
        penalty = CubeTypeConverters.fromPenalty(this.penalty),
        solvedAt = iso,
        scramble = this.scramble,
        version = version,
        updatedAt = iso,
        deletedAt = deletedAt
    )
}

fun SolveEntity.toSyncPayload(): SolveSyncPayload = SolveSyncPayload(
    id = this.id,
    sessionId = this.sessionId,
    durationMs = this.durationMs,
    penalty = this.penalty,
    solvedAt = this.solvedAt,
    scramble = this.scramble,
    event = this.event
)

fun SolveTime.toSyncPayload(): SolveSyncPayload = SolveSyncPayload(
    id = this.id,
    sessionId = this.sessionId,
    durationMs = this.timeInMillis,
    penalty = CubeTypeConverters.fromPenalty(this.penalty),
    solvedAt = CubeTypeConverters.epochMillisToIso(this.timestamp),
    scramble = this.scramble,
    event = CubeTypeConverters.fromMode(this.mode)
)

fun Mode.toEventString(): String = CubeTypeConverters.fromMode(this)
fun String.toMode(): Mode = CubeTypeConverters.toMode(this)
fun Penalty.toDbString(): String = CubeTypeConverters.fromPenalty(this)
fun String.toPenalty(): Penalty = CubeTypeConverters.toPenalty(this)
