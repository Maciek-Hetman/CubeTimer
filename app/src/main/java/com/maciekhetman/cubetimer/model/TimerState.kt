package com.maciekhetman.cubetimer.model

sealed class TimerState {
    object Idle : TimerState()
    data class Holding(val progress: Float) : TimerState()
    object Ready : TimerState()
    data class Running(val elapsedTime: Long) : TimerState()
    data class Finished(val time: Long) : TimerState()
}
