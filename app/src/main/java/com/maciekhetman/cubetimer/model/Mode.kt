package com.maciekhetman.cubetimer.model

enum class Mode {
    CUBE_2x2,
    CUBE_3x3,
    CUBE_4x4,
    CUBE_5x5,
    MEGAMINX,
    PYRAMINX;

    val displayName: String
        get() = when (this) {
            CUBE_2x2 -> "2x2"
            CUBE_3x3 -> "3x3"
            CUBE_4x4 -> "4x4"
            CUBE_5x5 -> "5x5"
            MEGAMINX -> "Megaminx"
            PYRAMINX -> "Pyraminx"
        }
}
