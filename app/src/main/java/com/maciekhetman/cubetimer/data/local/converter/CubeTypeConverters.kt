package com.maciekhetman.cubetimer.data.local.converter

import androidx.room.TypeConverter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class CubeTypeConverters {

    // --- Mode Converters ---
    @TypeConverter
    fun fromMode(mode: Mode?): String {
        return when (mode) {
            Mode.CUBE_2x2 -> "2x2"
            Mode.CUBE_3x3 -> "3x3"
            Mode.CUBE_4x4 -> "4x4"
            Mode.CUBE_5x5 -> "5x5"
            Mode.MEGAMINX -> "megaminx"
            Mode.PYRAMINX -> "pyraminx"
            null -> "3x3"
        }
    }

    @TypeConverter
    fun toMode(value: String?): Mode {
        return when (value?.lowercase()?.trim()) {
            "2x2", "cube_2x2" -> Mode.CUBE_2x2
            "3x3", "cube_3x3" -> Mode.CUBE_3x3
            "4x4", "cube_4x4" -> Mode.CUBE_4x4
            "5x5", "cube_5x5" -> Mode.CUBE_5x5
            "megaminx" -> Mode.MEGAMINX
            "pyraminx" -> Mode.PYRAMINX
            else -> Mode.CUBE_3x3
        }
    }

    // --- Penalty Converters ---
    @TypeConverter
    fun fromPenalty(penalty: Penalty?): String {
        return when (penalty) {
            Penalty.NONE -> "none"
            Penalty.PLUS_TWO -> "plus_two"
            Penalty.DNF -> "dnf"
            null -> "none"
        }
    }

    @TypeConverter
    fun toPenalty(value: String?): Penalty {
        return when (value?.lowercase()?.trim()) {
            "none" -> Penalty.NONE
            "plus_two", "+2", "plus2" -> Penalty.PLUS_TWO
            "dnf" -> Penalty.DNF
            else -> Penalty.NONE
        }
    }

    // --- Timestamp Helpers (RFC 3339 / ISO 8601 UTC) ---
    @TypeConverter
    fun instantToString(instant: Instant?): String? {
        return instant?.toString()
    }

    @TypeConverter
    fun stringToInstant(value: String?): Instant? {
        return value?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        /** Duration added to a solve's raw time when it carries a "+2" penalty. */
        const val PLUS_TWO_PENALTY_MS: Long = 2000L

        // Single shared instance backing the static helpers below so they don't allocate a new
        // CubeTypeConverters() on every call (these are invoked per-row from the mappers).
        private val instance = CubeTypeConverters()

        private val ISO_MILLIS_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * Fixed millisecond-precision ISO-8601 UTC string (e.g. "2026-08-30T10:00:00.000Z").
         * Instant.toString() omits the fractional part when millis == 0, which breaks
         * lexicographic ordering of `solved_at` strings used by `ORDER BY solved_at` and
         * `solved_at < :solvedAt` queries; this always emits ".SSS".
         */
        fun epochMillisToIso(epochMillis: Long): String {
            return ISO_MILLIS_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
        }

        /**
         * Parses an ISO-8601 timestamp to epoch millis. Returns 0L (rather than "now") for a
         * blank or unparsable string so a corrupted timestamp doesn't silently become "now" on
         * every read (which would also re-persist a drifting value on next save).
         */
        /** Current time as a fixed millisecond-precision ISO-8601 UTC string. */
        fun nowIso(): String = epochMillisToIso(System.currentTimeMillis())

        fun isoToEpochMillis(isoString: String?): Long {
            if (isoString.isNullOrBlank()) return 0L
            return try {
                Instant.parse(isoString).toEpochMilli()
            } catch (e: Exception) {
                0L
            }
        }

        // Static helpers for non-Room callers
        fun fromMode(mode: Mode?): String = instance.fromMode(mode)
        fun toMode(value: String?): Mode = instance.toMode(value)
        fun fromPenalty(penalty: Penalty?): String = instance.fromPenalty(penalty)
        fun toPenalty(value: String?): Penalty = instance.toPenalty(value)
        fun instantToString(instant: Instant?): String? = instance.instantToString(instant)
        fun stringToInstant(value: String?): Instant? = instance.stringToInstant(value)
    }
}
