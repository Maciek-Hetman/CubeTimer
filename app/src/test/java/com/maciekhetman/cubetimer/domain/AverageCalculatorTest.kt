package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AverageCalculatorTest {
    @Test
    fun averageOfN_trimsBestAndWorstSolve() {
        val solves = listOf(
            solve(10_000),
            solve(11_000),
            solve(12_000),
            solve(13_000),
            solve(14_000)
        )

        assertEquals(12_000L, AverageCalculator.averageOfN(solves, 5))
    }

    @Test
    fun averageOfN_allowsOneDnfAsWorstSolve() {
        val solves = listOf(
            solve(10_000),
            solve(11_000),
            solve(12_000),
            solve(13_000),
            solve(14_000, Penalty.DNF)
        )

        assertEquals(12_000L, AverageCalculator.averageOfN(solves, 5))
    }

    @Test
    fun averageOfN_returnsNullForMultipleDnfs() {
        val solves = listOf(
            solve(10_000),
            solve(11_000),
            solve(12_000),
            solve(13_000, Penalty.DNF),
            solve(14_000, Penalty.DNF)
        )

        assertNull(AverageCalculator.averageOfN(solves, 5))
    }

    private fun solve(timeInMillis: Long, penalty: Penalty = Penalty.NONE): SolveTime {
        return SolveTime(timeInMillis = timeInMillis, penalty = penalty)
    }
}
