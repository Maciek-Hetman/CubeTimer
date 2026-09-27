package com.maciekhetman.cubetimer.model

/**
 * What measured a solve. Persisted and synced as the CubeSync `timing_device` string.
 */
enum class TimingDevice(val value: String, val displayName: String) {
    /** On-screen touch timing (the API calls this "keyboard"). */
    KEYBOARD("keyboard", "Screen / Touch"),
    EXTERNAL_TIMER("external_timer", "Bluetooth timer"),
    SMART_CUBE("smart_cube", "Smart cube");

    companion object {
        fun fromString(value: String?): TimingDevice =
            entries.firstOrNull { it.value == value?.lowercase()?.trim() } ?: KEYBOARD
    }
}
