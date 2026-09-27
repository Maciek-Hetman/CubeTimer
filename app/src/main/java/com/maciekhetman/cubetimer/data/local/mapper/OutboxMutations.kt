package com.maciekhetman.cubetimer.data.local.mapper

import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Shared helpers for building [SyncOutboxEntity] mutations for solves and sessions.
 *
 * Centralizes the `SyncOutboxEntity(id = UUID.randomUUID()..., action = "upsert"/"delete", ...)`
 * construction that was previously hand-written at every call site across
 * SolvesRepository, SessionRepositoryImpl, ConflictResolverImpl and CsvImporter.
 */

fun SolveEntity.toUpsertMutation(
    ownerId: String = this.ownerId,
    clientTime: String,
    json: Json = NetworkModule.json
): SyncOutboxEntity = SyncOutboxEntity(
    id = UUID.randomUUID().toString(),
    ownerId = ownerId,
    entityType = "solve",
    entityId = this.id,
    action = "upsert",
    baseVersion = this.version,
    payloadJson = json.encodeToString(SolveSyncPayload.serializer(), this.toSyncPayload()),
    clientTime = clientTime,
    status = "pending"
)

fun SolveEntity.toDeleteMutation(
    ownerId: String = this.ownerId,
    clientTime: String
): SyncOutboxEntity = solveDeleteMutation(
    entityId = this.id,
    ownerId = ownerId,
    baseVersion = this.version,
    clientTime = clientTime
)

/**
 * Builds a "solve" delete mutation from an id/version pair rather than a full [SolveEntity],
 * for call sites (e.g. conflict resolution) that only have those on hand.
 */
fun solveDeleteMutation(
    entityId: String,
    ownerId: String,
    baseVersion: Long,
    clientTime: String
): SyncOutboxEntity = SyncOutboxEntity(
    id = UUID.randomUUID().toString(),
    ownerId = ownerId,
    entityType = "solve",
    entityId = entityId,
    action = "delete",
    baseVersion = baseVersion,
    payloadJson = null,
    clientTime = clientTime,
    status = "pending"
)

fun SessionEntity.toUpsertMutation(
    ownerId: String = this.ownerId,
    clientTime: String,
    json: Json = NetworkModule.json
): SyncOutboxEntity = SyncOutboxEntity(
    id = UUID.randomUUID().toString(),
    ownerId = ownerId,
    entityType = "session",
    entityId = this.id,
    action = "upsert",
    baseVersion = this.version,
    payloadJson = json.encodeToString(SessionSyncPayload.serializer(), this.toSyncPayload()),
    clientTime = clientTime,
    status = "pending"
)

fun SessionEntity.toDeleteMutation(
    ownerId: String = this.ownerId,
    clientTime: String
): SyncOutboxEntity = sessionDeleteMutation(
    entityId = this.id,
    ownerId = ownerId,
    baseVersion = this.version,
    clientTime = clientTime
)

/**
 * Builds a "session" delete mutation from an id/version pair rather than a full [SessionEntity],
 * for call sites (e.g. conflict resolution) that only have those on hand.
 */
fun sessionDeleteMutation(
    entityId: String,
    ownerId: String,
    baseVersion: Long,
    clientTime: String
): SyncOutboxEntity = SyncOutboxEntity(
    id = UUID.randomUUID().toString(),
    ownerId = ownerId,
    entityType = "session",
    entityId = entityId,
    action = "delete",
    baseVersion = baseVersion,
    payloadJson = null,
    clientTime = clientTime,
    status = "pending"
)
