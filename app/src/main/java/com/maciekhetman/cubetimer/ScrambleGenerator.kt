package com.maciekhetman.cubetimer

import kotlin.random.Random

object ScrambleGenerator {
    private val moves3x3 = listOf("R", "L", "U", "D", "F", "B")
    private val moves2x2 = listOf("R", "U", "F")
    private val moves4x4 = listOf("R", "L", "U", "D", "F", "B", "Rw", "Lw", "Uw", "Dw", "Fw", "Bw")
    private val moves5x5 = listOf("R", "L", "U", "D", "F", "B", "Rw", "Lw", "Uw", "Dw", "Fw", "Bw")
    private val movesMegaminx = listOf("R++", "R--", "D++", "D--", "U")
    private val movesPyraminx = listOf("U", "L", "R", "B")
    private val modifiers = listOf("", "'", "2")
    private val pyraminxModifiers = listOf("", "'")
    
    fun generateScramble(mode: Mode): String {
        return when (mode) {
            Mode.CUBE_2x2 -> generate2x2Scramble()
            Mode.CUBE_3x3 -> generate3x3Scramble()
            Mode.CUBE_4x4 -> generate4x4Scramble()
            Mode.CUBE_5x5 -> generate5x5Scramble()
            Mode.MEGAMINX -> generateMegaminxScramble()
            Mode.PYRAMINX -> generatePyraminxScramble()
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
    
    fun generate4x4Scramble(): String {
        val scramble = mutableListOf<String>()
        var lastMove: String? = null
        var lastAxis: String? = null
        
        repeat(40) { // WCA standard is 40 moves for 4x4
            var move: String
            var axis: String
            
            do {
                move = moves4x4.random()
                axis = when {
                    move.startsWith("R") || move.startsWith("L") -> "RL"
                    move.startsWith("U") || move.startsWith("D") -> "UD"
                    move.startsWith("F") || move.startsWith("B") -> "FB"
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
    
    fun generate5x5Scramble(): String {
        val scramble = mutableListOf<String>()
        var lastMove: String? = null
        var lastAxis: String? = null
        
        repeat(60) { // WCA standard is 60 moves for 5x5
            var move: String
            var axis: String
            
            do {
                move = moves5x5.random()
                axis = when {
                    move.startsWith("R") || move.startsWith("L") -> "RL"
                    move.startsWith("U") || move.startsWith("D") -> "UD"
                    move.startsWith("F") || move.startsWith("B") -> "FB"
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
    
    fun generateMegaminxScramble(): String {
        val scramble = mutableListOf<String>()
        
        repeat(70) { // 70 moves for Megaminx
            val move = movesMegaminx.random()
            scramble.add(move)
        }
        
        return scramble.joinToString(" ")
    }
    
    fun generatePyraminxScramble(): String {
        val scramble = mutableListOf<String>()
        var lastMove: String? = null
        
        repeat(11) { // 11 moves for Pyraminx
            var move: String
            
            do {
                move = movesPyraminx.random()
            } while (move == lastMove)
            
            val modifier = pyraminxModifiers.random()
            scramble.add(move + modifier)
            
            lastMove = move
        }
        
        // Add tips
        val tips = listOf("u", "l", "r", "b")
        val tipScramble = tips.map { tip ->
            if (Random.nextBoolean()) tip + pyraminxModifiers.random() else ""
        }.filter { it.isNotEmpty() }
        
        return (scramble + tipScramble).joinToString(" ")
    }
}
