package com.maciekhetman.cubetimer.viewmodel

import android.content.res.Resources
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.remote.dto.SessionSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SessionSyncPayload
import com.maciekhetman.cubetimer.data.remote.dto.SolveSnapshotDto
import com.maciekhetman.cubetimer.data.remote.dto.SolveSyncPayload
import com.maciekhetman.cubetimer.domain.TimeFormatter
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One side ("This device" or "Server") of a sync conflict, already formatted for display. */
data class ConflictSideUi(
    val deleted: Boolean,
    val lines: List<String>
)

/** A sync conflict as shown in the conflict list. */
data class ConflictUiModel(
    val id: String,
    val title: String,
    val local: ConflictSideUi,
    val server: ConflictSideUi
)

/** The words [ConflictUiMapper] puts into conflict lines; English unless built from resources. */
data class ConflictLabels(
    val session: String = "Session",
    val solve: String = "Solve",
    val deleted: String = ConflictUiMapper.DELETED_LINE,
    val unreadable: String = ConflictUiMapper.UNREADABLE_LINE,
    val archived: String = "Archived",
    val active: String = "Active"
) {
    companion object {
        fun from(resources: Resources) = ConflictLabels(
            session = resources.getString(R.string.sync_conflict_session),
            solve = resources.getString(R.string.sync_conflict_solve),
            deleted = resources.getString(R.string.sync_conflict_deleted),
            unreadable = resources.getString(R.string.sync_conflict_unreadable),
            archived = resources.getString(R.string.sync_conflict_archived),
            active = resources.getString(R.string.sync_conflict_active)
        )
    }
}

/**
 * Turns [ConflictEntity] rows into readable [ConflictUiModel]s. Payloads that can't be decoded
 * fall back to a generic line instead of failing.
 */
object ConflictUiMapper {
    const val DELETED_LINE = "Deleted"
    const val UNREADABLE_LINE = "Details unavailable"

    private val KNOWN_EVENTS = setOf("2x2", "3x3", "4x4", "5x5", "megaminx", "pyraminx")

    fun map(
        conflict: ConflictEntity,
        json: Json = NetworkModule.json,
        timeZone: TimeZone = TimeZone.getDefault(),
        labels: ConflictLabels = ConflictLabels()
    ): ConflictUiModel {
        val isSession = conflict.entityType == "session"
        val local: ConflictSideUi
        val server: ConflictSideUi
        if (isSession) {
            local = sessionSide(conflict.localPayloadJson, labels) { raw ->
                json.decodeFromString<SessionSyncPayload>(raw).let {
                    SessionFields(it.name, it.event, it.archived, deletedAt = null)
                }
            }
            server = sessionSide(conflict.serverPayloadJson, labels) { raw ->
                json.decodeFromString<SessionSnapshotDto>(raw).let {
                    SessionFields(it.name, it.event, it.archived, it.deletedAt)
                }
            }
        } else {
            local = solveSide(conflict.localPayloadJson, timeZone, labels) { raw ->
                json.decodeFromString<SolveSyncPayload>(raw).let {
                    SolveFields(it.event, it.durationMs, it.penalty, it.solvedAt, deletedAt = null)
                }
            }
            server = solveSide(conflict.serverPayloadJson, timeZone, labels) { raw ->
                json.decodeFromString<SolveSnapshotDto>(raw).let {
                    SolveFields(it.event, it.durationMs, it.penalty, it.solvedAt, it.deletedAt)
                }
            }
        }
        return ConflictUiModel(
            id = conflict.conflictId,
            title = if (isSession) labels.session else labels.solve,
            local = local,
            server = server
        )
    }

    fun formatSolveTime(durationMs: Long, penalty: String): String = when (penalty) {
        "dnf" -> "DNF (${TimeFormatter.formatTime(durationMs)})"
        "plus_two" -> "${TimeFormatter.formatTime(durationMs + 2000L)} (+2)"
        else -> TimeFormatter.formatTime(durationMs)
    }

    fun formatEvent(event: String): String =
        if (event.lowercase(Locale.ROOT) in KNOWN_EVENTS) {
            CubeTypeConverters.toMode(event.lowercase(Locale.ROOT)).displayName
        } else {
            event
        }

    private data class SolveFields(
        val event: String,
        val durationMs: Long,
        val penalty: String,
        val solvedAt: String,
        val deletedAt: String?
    )

    private data class SessionFields(
        val name: String,
        val event: String,
        val archived: Boolean,
        val deletedAt: String?
    )

    private inline fun solveSide(
        raw: String?,
        timeZone: TimeZone,
        labels: ConflictLabels,
        decode: (String) -> SolveFields
    ): ConflictSideUi {
        if (raw.isNullOrBlank() || raw == "null") return ConflictSideUi(deleted = true, lines = listOf(labels.deleted))
        val fields = runCatching { decode(raw) }.getOrNull()
            ?: return ConflictSideUi(deleted = false, lines = listOf(labels.unreadable))
        if (fields.deletedAt != null) return ConflictSideUi(deleted = true, lines = listOf(labels.deleted))
        val lines = buildList {
            add("${formatEvent(fields.event)} · ${formatSolveTime(fields.durationMs, fields.penalty)}")
            formatDate(fields.solvedAt, timeZone)?.let { add(it) }
        }
        return ConflictSideUi(deleted = false, lines = lines)
    }

    private inline fun sessionSide(
        raw: String?,
        labels: ConflictLabels,
        decode: (String) -> SessionFields
    ): ConflictSideUi {
        if (raw.isNullOrBlank() || raw == "null") return ConflictSideUi(deleted = true, lines = listOf(labels.deleted))
        val fields = runCatching { decode(raw) }.getOrNull()
            ?: return ConflictSideUi(deleted = false, lines = listOf(labels.unreadable))
        if (fields.deletedAt != null) return ConflictSideUi(deleted = true, lines = listOf(labels.deleted))
        val lines = listOf(
            fields.name,
            "${formatEvent(fields.event)} · ${if (fields.archived) labels.archived else labels.active}"
        )
        return ConflictSideUi(deleted = false, lines = lines)
    }

    private fun formatDate(iso: String, timeZone: TimeZone): String? {
        val millis = CubeTypeConverters.isoToEpochMillis(iso)
        if (millis <= 0L) return null
        val format = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        format.timeZone = timeZone
        return format.format(Date(millis))
    }
}
