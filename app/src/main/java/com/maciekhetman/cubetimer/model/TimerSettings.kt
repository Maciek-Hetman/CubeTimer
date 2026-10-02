package com.maciekhetman.cubetimer.model

import androidx.annotation.StringRes
import com.maciekhetman.cubetimer.R

val TimerAverageOptions = listOf(5, 12, 25, 50, 100)

enum class RunningTimerDisplay(@StringRes val labelRes: Int) {
    FULL(R.string.running_timer_display_full),
    SECONDS_ONLY(R.string.running_timer_display_seconds),
    HIDDEN(R.string.running_timer_display_hidden)
}

/** The touch timer's start delay (how long to hold before it is ready), also used to start a solve from inspection. */
const val TIMER_START_DELAY_MAX_MILLIS = 500
const val TIMER_START_DELAY_STEP_MILLIS = 50

/** [delayMillis] clamped to 0..[TIMER_START_DELAY_MAX_MILLIS] and rounded to a [TIMER_START_DELAY_STEP_MILLIS] step. */
fun normalizeTimerStartDelayMillis(delayMillis: Int): Int {
    val step = TIMER_START_DELAY_STEP_MILLIS
    return (delayMillis.coerceIn(0, TIMER_START_DELAY_MAX_MILLIS) + step / 2) / step * step
}
