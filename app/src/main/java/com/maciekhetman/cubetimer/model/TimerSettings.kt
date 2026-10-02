package com.maciekhetman.cubetimer.model

val TimerAverageOptions = listOf(5, 12, 25, 50, 100)

enum class RunningTimerDisplay(val displayName: String) {
    FULL("Show decimals"),
    SECONDS_ONLY("Hide decimals"),
    HIDDEN("Hide timer")
}

/** The touch timer's start delay (how long to hold before it is ready), also used to start a solve from inspection. */
const val TIMER_START_DELAY_MAX_MILLIS = 500
const val TIMER_START_DELAY_STEP_MILLIS = 50

/** [delayMillis] clamped to 0..[TIMER_START_DELAY_MAX_MILLIS] and rounded to a [TIMER_START_DELAY_STEP_MILLIS] step. */
fun normalizeTimerStartDelayMillis(delayMillis: Int): Int {
    val step = TIMER_START_DELAY_STEP_MILLIS
    return (delayMillis.coerceIn(0, TIMER_START_DELAY_MAX_MILLIS) + step / 2) / step * step
}
