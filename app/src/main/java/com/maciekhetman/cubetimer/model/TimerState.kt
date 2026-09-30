package com.maciekhetman.cubetimer.model

sealed class TimerState {
    object Idle : TimerState()

    /**
     * The optional WCA inspection countdown before a touch solve. [elapsedMillis] is the inspection
     * time so far. [holdProgress] is null while no finger is down for the start gesture, otherwise
     * how far the start-delay hold has got (1f = ready to release and start).
     */
    data class Inspecting(val elapsedMillis: Long, val holdProgress: Float? = null) : TimerState() {
        val isHolding: Boolean get() = holdProgress != null && holdProgress < 1f
        val isReady: Boolean get() = holdProgress != null && holdProgress >= 1f
        val penalty: Penalty get() = Inspection.penaltyFor(elapsedMillis)
    }

    data class Holding(val progress: Float) : TimerState()
    object Ready : TimerState()
    data class Running(val elapsedTime: Long) : TimerState()

    /**
     * [timingDevice] is what measured [time]; it is saved with the solve. [inspectionPenalty] is what
     * inspection ran over by when the solve started; saving keeps the worse of it and the chosen penalty.
     */
    data class Finished(
        val time: Long,
        val timingDevice: TimingDevice = TimingDevice.KEYBOARD,
        val inspectionPenalty: Penalty = Penalty.NONE
    ) : TimerState()
}

/** WCA inspection: 15 s, +2 from 15 s, DNF from 17 s. */
object Inspection {
    const val DURATION_MILLIS = 15_000L
    const val DNF_AFTER_MILLIS = 17_000L

    fun penaltyFor(elapsedMillis: Long): Penalty = when {
        elapsedMillis >= DNF_AFTER_MILLIS -> Penalty.DNF
        elapsedMillis >= DURATION_MILLIS -> Penalty.PLUS_TWO
        else -> Penalty.NONE
    }

    /** Whole seconds left (15 down to 1), then "+2" and "DNF" once inspection has run over. */
    fun displayText(elapsedMillis: Long): String = when (penaltyFor(elapsedMillis)) {
        Penalty.NONE -> ((DURATION_MILLIS - elapsedMillis.coerceAtLeast(0L) + 999L) / 1000L).toString()
        Penalty.PLUS_TWO -> "+2"
        Penalty.DNF -> "DNF"
    }

    /** The worse of two penalties (DNF > +2 > none). */
    fun moreSevere(a: Penalty, b: Penalty): Penalty = if (a.ordinal >= b.ordinal) a else b
}
