package com.maciekhetman.cubetimer

import org.worldcubeassociation.tnoodle.scrambles.PuzzleRegistry

object ScrambleGenerator {
    private val scramblersByMode = mapOf(
        Mode.CUBE_2x2 to PuzzleRegistry.TWO,
        Mode.CUBE_3x3 to PuzzleRegistry.THREE,
        Mode.CUBE_4x4 to PuzzleRegistry.FOUR,
        Mode.CUBE_5x5 to PuzzleRegistry.FIVE,
        Mode.MEGAMINX to PuzzleRegistry.MEGA,
        Mode.PYRAMINX to PuzzleRegistry.PYRA
    )

    fun generateScramble(mode: Mode): String {
        val registry = scramblersByMode.getValue(mode)
        return registry.getScrambler().generateScramble()
    }
}
