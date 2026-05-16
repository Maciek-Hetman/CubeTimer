package com.maciekhetman.cubetimer.model

enum class RecordType {
    BEST_SINGLE,
    BEST_AO5,
    BEST_AO12
}

data class RecordCelebration(
    val type: RecordType,
    val time: Long
)
