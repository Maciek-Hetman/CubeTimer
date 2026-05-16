package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import kotlin.math.pow
import kotlin.math.sqrt

object AverageCalculator {
    fun averageOfN(solves: List<SolveTime>, count: Int): Long? {
        if (solves.size < count) return null
        return averageWindow(solves.takeLast(count))
    }

    fun bestAverageOfN(solves: List<SolveTime>, count: Int): Long? {
        if (solves.size < count) return null

        return (0..(solves.size - count))
            .mapNotNull { start -> averageWindow(solves.subList(start, start + count)) }
            .minOrNull()
    }

    fun mean(solves: List<SolveTime>): Long {
        val validSolves = solves.filter { it.penalty != Penalty.DNF }
        if (validSolves.isEmpty()) return 0L
        return validSolves.map { it.displayTime }.average().toLong()
    }

    fun average(solves: List<SolveTime>): Long {
        return mean(solves)
    }

    fun standardDeviation(solves: List<SolveTime>): Double {
        val validSolves = solves.filter { it.penalty != Penalty.DNF }
        if (validSolves.size < 2) return 0.0

        val times = validSolves.map { it.displayTime.toDouble() }
        val mean = times.average()
        val variance = times.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    private fun averageWindow(window: List<SolveTime>): Long? {
        if (window.size < 3) {
            val validTimes = window
                .filter { it.penalty != Penalty.DNF }
                .map { it.displayTime }
            return validTimes.takeIf { it.isNotEmpty() }?.average()?.toLong()
        }

        val validTimes = window
            .filter { it.penalty != Penalty.DNF }
            .map { it.displayTime }
            .sorted()
        val dnfCount = window.size - validTimes.size

        if (dnfCount > 1 || validTimes.size < window.size - 1) return null

        val trimmedTimes = if (dnfCount == 1) {
            validTimes.drop(1)
        } else {
            validTimes.drop(1).dropLast(1)
        }

        return trimmedTimes.takeIf { it.isNotEmpty() }?.average()?.toLong()
    }
}
