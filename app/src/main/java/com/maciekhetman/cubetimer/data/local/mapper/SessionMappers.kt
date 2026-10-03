package com.maciekhetman.cubetimer.data.local.mapper

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind

fun SessionEntity.toDomain(): Session = Session(
    id = this.id,
    ownerId = this.ownerId,
    name = this.name,
    event = CubeTypeConverters.toMode(this.event),
    kind = SessionKind.fromString(this.kind),
    archived = this.archived,
    startedAt = this.startedAt,
    endedAt = this.endedAt,
    version = this.version,
    updatedAt = this.updatedAt,
    deletedAt = this.deletedAt
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = this.id,
    ownerId = this.ownerId,
    name = this.name,
    event = CubeTypeConverters.fromMode(this.event),
    kind = this.kind.value,
    startedAt = this.startedAt,
    endedAt = this.endedAt,
    archived = this.archived,
    version = this.version,
    updatedAt = this.updatedAt,
    deletedAt = this.deletedAt
)

fun SessionEntity.toSyncPayload(): SessionSyncPayload = SessionSyncPayload(
    id = this.id,
    name = this.name,
    event = this.event,
    kind = this.kind,
    startedAt = this.startedAt,
    endedAt = this.endedAt,
    archived = this.archived
)

/**
 * The local row for a session the server sent. [version] and [updatedAt] are passed in rather than
 * read from the DTO because each source settles them differently (a sync change, a snapshot page, a
 * conflict's server copy).
 */
fun SessionSnapshotDto.toEntity(ownerId: String, version: Long, updatedAt: String): SessionEntity = SessionEntity(
    id = this.id,
    ownerId = ownerId,
    name = this.name,
    event = this.event,
    kind = this.kind,
    startedAt = this.startedAt,
    endedAt = this.endedAt,
    archived = this.archived,
    version = version,
    updatedAt = updatedAt,
    deletedAt = this.deletedAt
)
