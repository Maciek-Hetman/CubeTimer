package com.maciekhetman.cubetimer.data.local.converter

import androidx.room.TypeConverter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import java.time.Instant
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
        fun epochMillisToIso(epochMillis: Long): String {
            return Instant.ofEpochMilli(epochMillis).toString()
        }

        fun isoToEpochMillis(isoString: String?): Long {
            if (isoString.isNullOrBlank()) return System.currentTimeMillis()
            return try {
                Instant.parse(isoString).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
        }

        // Static helpers for non-Room callers
        fun fromMode(mode: Mode?): String = CubeTypeConverters().fromMode(mode)
        fun toMode(value: String?): Mode = CubeTypeConverters().toMode(value)
        fun fromPenalty(penalty: Penalty?): String = CubeTypeConverters().fromPenalty(penalty)
        fun toPenalty(value: String?): Penalty = CubeTypeConverters().toPenalty(value)
        fun instantToString(instant: Instant?): String? = CubeTypeConverters().instantToString(instant)
        fun stringToInstant(value: String?): Instant? = CubeTypeConverters().stringToInstant(value)
    }
}
