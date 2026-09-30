package com.maciekhetman.cubetimer.model

val TimerAverageOptions = listOf(5, 12, 25, 50, 100)

enum class RunningTimerDisplay(val displayName: String) {
    FULL("Show decimals"),
    SECONDS_ONLY("Hide decimals"),
    HIDDEN("Hide timer")
}

/** How a solve is started once inspection is running. */
enum class InspectionStartGesture(val displayName: String) {
    HOLD("Tap and hold"),
    TAP("Tap")
}
