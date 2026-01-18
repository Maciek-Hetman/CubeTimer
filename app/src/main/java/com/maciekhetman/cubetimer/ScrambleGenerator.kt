package com.maciekhetman.cubetimer

import kotlin.random.Random

object ScrambleGenerator {
    private val moves3x3 = listOf("R", "L", "U", "D", "F", "B")
    private val moves2x2 = listOf("R", "U", "F")
    private val modifiers = listOf("", "'", "2")
    
    fun generateScramble(mode: Mode): String {
        return when (mode) {
            Mode.CUBE_3x3 -> generate3x3Scramble()
            Mode.CUBE_2x2 -> generate2x2Scramble()
        }
    }
    
    fun generate3x3Scramble(): String {
        val scramble = mutableListOf<String>()
        var lastMove: String? = null
        var lastAxis: String? = null
        
        repeat(20) { // WCA standard is 20 moves for 3x3
            var move: String
            var axis: String
            
            do {
                move = moves3x3.random()
                axis = when (move) {
                    "R", "L" -> "RL"
                    "U", "D" -> "UD"
                    "F", "B" -> "FB"
                    else -> ""
                }
            } while (move == lastMove || (lastAxis != null && axis == lastAxis))
            
            val modifier = modifiers.random()
            scramble.add(move + modifier)
            
            lastMove = move
            lastAxis = axis
        }
        
        return scramble.joinToString(" ")
    }
    
    fun generate2x2Scramble(): String {
        val scramble = mutableListOf<String>()
        var lastMove: String? = null
        
        repeat(9) { // WCA standard is 9 moves for 2x2
            var move: String
            
            do {
                move = moves2x2.random()
            } while (move == lastMove)
            
            val modifier = modifiers.random()
            scramble.add(move + modifier)
            
            lastMove = move
        }
        
        return scramble.joinToString(" ")
    }
}
