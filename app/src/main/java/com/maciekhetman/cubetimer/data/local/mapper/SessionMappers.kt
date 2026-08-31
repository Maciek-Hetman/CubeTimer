package com.maciekhetman.cubetimer.data.local.mapper

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.model.Mode
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

fun Session.toSyncPayload(): SessionSyncPayload = SessionSyncPayload(
    id = this.id,
    name = this.name,
    event = CubeTypeConverters.fromMode(this.event),
    kind = this.kind.value,
    startedAt = this.startedAt,
    endedAt = this.endedAt,
    archived = this.archived
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
