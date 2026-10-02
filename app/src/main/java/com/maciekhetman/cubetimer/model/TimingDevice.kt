package com.maciekhetman.cubetimer.model

import androidx.annotation.StringRes
import com.maciekhetman.cubetimer.R

/**
 * What measured a solve. Persisted and synced as the CubeSync `timing_device` string.
 */
enum class TimingDevice(val value: String, @StringRes val labelRes: Int) {
    /** On-screen touch timing (the API calls this "keyboard"). */
    KEYBOARD("keyboard", R.string.timing_device_label_touch),
    EXTERNAL_TIMER("external_timer", R.string.bluetooth_timer),
    SMART_CUBE("smart_cube", R.string.timing_device_label_smart_cube);

    companion object {
        fun fromString(value: String?): TimingDevice =
            entries.firstOrNull { it.value == value?.lowercase()?.trim() } ?: KEYBOARD
    }
}
