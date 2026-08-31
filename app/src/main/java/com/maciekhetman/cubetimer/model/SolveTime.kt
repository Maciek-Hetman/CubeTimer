package com.maciekhetman.cubetimer.model

import java.util.UUID

data class SolveTime(
    val id: String = UUID.randomUUID().toString(),
    val timeInMillis: Long,
    val penalty: Penalty = Penalty.NONE,
    val timestamp: Long = System.currentTimeMillis(),
    val scramble: String = "",
    val mode: Mode = Mode.CUBE_3x3,
    val sessionId: String? = null
) {
    val displayTime: Long
        get() = when (penalty) {
            Penalty.NONE -> timeInMillis
            Penalty.PLUS_TWO -> timeInMillis + 2000
            Penalty.DNF -> timeInMillis
        }
}
