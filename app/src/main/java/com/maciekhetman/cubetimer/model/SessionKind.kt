package com.maciekhetman.cubetimer.model

/**
 * Defines whether a session is managed automatically by time grouping or created manually by the user.
 */
enum class SessionKind(val value: String) {
    AUTOMATIC("automatic"),
    MANUAL("manual");

    companion object {
        fun fromString(value: String?): SessionKind = when (value?.lowercase()?.trim()) {
            "manual" -> MANUAL
            "automatic" -> AUTOMATIC
            else -> AUTOMATIC
        }
    }
}
