package com.maciekhetman.cubetimer.domain

import java.util.Locale

object TimeFormatter {
    fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val milliseconds = (millis % 1000) / 10

        return if (totalSeconds >= 60) {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, milliseconds)
        } else {
            String.format(Locale.ROOT, "%d.%02d", totalSeconds, milliseconds)
        }
    }

    fun splitTimerTime(millis: Long): Pair<String, String> {
        if (millis == 0L) return "0" to ".00"

        val totalSeconds = millis / 1000
        val milliseconds = (millis % 1000) / 10

        return if (totalSeconds >= 60) {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds) to
                String.format(Locale.ROOT, ".%02d", milliseconds)
        } else {
            String.format(Locale.ROOT, "%d", totalSeconds) to
                String.format(Locale.ROOT, ".%02d", milliseconds)
        }
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format(Locale.ROOT, "%dh %dm", hours, minutes)
            minutes > 0 -> String.format(Locale.ROOT, "%dm %ds", minutes, seconds)
            else -> String.format(Locale.ROOT, "%ds", seconds)
        }
    }
}
