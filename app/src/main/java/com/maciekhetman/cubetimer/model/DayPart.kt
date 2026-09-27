package com.maciekhetman.cubetimer.model

import java.time.LocalTime

/**
 * Enumeration of day partitions matching CubeTimer-web reference specification:
 * - MORNING: 05:00:00 - 11:59:59 (inclusive bounds: 5..11)
 * - AFTERNOON: 12:00:00 - 16:59:59 (inclusive bounds: 12..16)
 * - EVENING: 17:00:00 - 21:59:59 (inclusive bounds: 17..21)
 * - NIGHT: 22:00:00 - 04:59:59 (inclusive bounds: 22..23 and 0..4)
 */
enum class DayPart(val value: String) {
    MORNING("morning"),
    AFTERNOON("afternoon"),
    EVENING("evening"),
    NIGHT("night");

    companion object {
        fun fromHour(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT // 22..23, 0..4
        }

        fun fromLocalTime(time: LocalTime): DayPart = fromHour(time.hour)

        fun fromString(value: String?): DayPart = when (value?.lowercase()?.trim()) {
            "morning" -> MORNING
            "afternoon" -> AFTERNOON
            "evening" -> EVENING
            "night" -> NIGHT
            else -> MORNING
        }
    }
}
