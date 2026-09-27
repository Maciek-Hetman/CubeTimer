package com.maciekhetman.cubetimer.model

sealed class TimerState {
    object Idle : TimerState()
    data class Holding(val progress: Float) : TimerState()
    object Ready : TimerState()
    data class Running(val elapsedTime: Long) : TimerState()

    /** [timingDevice] is what measured [time]; it is saved with the solve. */
    data class Finished(val time: Long, val timingDevice: TimingDevice = TimingDevice.KEYBOARD) : TimerState()
}
