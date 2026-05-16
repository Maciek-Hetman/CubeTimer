package com.maciekhetman.cubetimer.model

data class SolveTime(
    val timeInMillis: Long,
    val penalty: Penalty = Penalty.NONE,
    val timestamp: Long = System.currentTimeMillis(),
    val scramble: String = "",
    val mode: Mode = Mode.CUBE_3x3
) {
    val displayTime: Long
        get() = when (penalty) {
            Penalty.NONE -> timeInMillis
            Penalty.PLUS_TWO -> timeInMillis + 2000
            Penalty.DNF -> timeInMillis
        }
}
