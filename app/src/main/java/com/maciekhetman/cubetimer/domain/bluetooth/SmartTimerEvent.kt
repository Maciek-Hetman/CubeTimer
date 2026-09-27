package com.maciekhetman.cubetimer.domain.bluetooth

/** Normalized state changes reported by every supported Bluetooth timer. */
sealed interface SmartTimerEvent {
    /** Timer reset / waiting for a new solve. */
    data object Idle : SmartTimerEvent

    /** QiYi only: the timer's own inspection countdown is running. */
    data object Inspection : SmartTimerEvent

    /** Both hands placed on the pads, hold time not reached yet. */
    data object HandsOn : SmartTimerEvent

    /** Hands lifted before the timer armed. */
    data object HandsOff : SmartTimerEvent

    /** Armed ("get set", green light): lifting the hands starts the solve. */
    data object GetSet : SmartTimerEvent

    data object Running : SmartTimerEvent

    /** Solve finished; [timeMs] is the time measured by the timer itself. */
    data class Stopped(val timeMs: Long) : SmartTimerEvent

    /** The Bluetooth link dropped. */
    data object Disconnected : SmartTimerEvent
}

enum class SmartTimerModel(val displayName: String) {
    GAN("GAN Smart Timer"),
    QIYI("QiYi Smart Timer")
}
