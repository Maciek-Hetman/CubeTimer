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

    // ---------------------------------------------------------------------------------------------
    // WINDOWS < 5 (Mo3 / Small Windows, 0 Trim)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun averageWindow_windowUnder5_doesNotTrim() {
        val solves = listOf(
            solve(10_000),
            solve(11_000),
            solve(12_000)
        )
        // Window size 3: 0 trim -> mean of 10_000, 11_000, 12_000 is 11_000
        assertEquals(11_000L, AverageCalculator.averageWindow(solves))
    }

    @Test
    fun averageWindow_windowUnder5_returnsNullOnAnyDnf() {
        val solves = listOf(
            solve(10_000),
            solve(11_000),
            solve(12_000, Penalty.DNF)
        )
        // Window size 3: 0 trim -> 0 DNFs allowed -> returns null
        assertNull(AverageCalculator.averageWindow(solves))
    }

    @Test
    fun averageWindow_emptyWindow_returnsNull() {
        assertNull(AverageCalculator.averageWindow(emptyList()))
    }

    // ---------------------------------------------------------------------------------------------
    // WINDOWS 5..19 (Ao12, Trim 1 Best & 1 Worst)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun averageOfN_ao12_trimsSingleBestAndWorst() {
        val solves = (0 until 12).map { i -> solve(10_000L + i * 1_000L) }
        // 12 solves: 10_000..21_000. Trims 10_000 and 21_000.
        // Mean of remaining 10 solves (11_000..20_000) = (11_000 + 20_000) / 2 = 15_500
        assertEquals(15_500L, AverageCalculator.averageOfN(solves, 12))
    }

    @Test
    fun averageOfN_ao12_allowsOneDnf() {
        val solves = (0 until 11).map { i -> solve(10_000L + i * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        // 11 valid solves (10_000..20_000) + 1 DNF. Trims 10_000 (best) and DNF (worst).
        // Mean of remaining 10 solves (11_000..20_000) = 15_500
        assertEquals(15_500L, AverageCalculator.averageOfN(solves, 12))
    }

    @Test
    fun averageOfN_ao12_returnsNullForTwoDnfs() {
        val solves = (0 until 10).map { i -> solve(10_000L + i * 1_000L) } + listOf(
            solve(0L, Penalty.DNF),
            solve(0L, Penalty.DNF)
        )
        assertNull(AverageCalculator.averageOfN(solves, 12))
    }

    // ---------------------------------------------------------------------------------------------
    // WINDOWS >= 20 (5% Proportional Trimming: Ao50, Ao100, Ao500, Ao1000, Ao2000)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun averageOfN_ao50_trimsTwoBestAndWorst() {
        // Ao50 trimCount = (50 * 0.05).toInt() = 2
        val solves = (1..50).map { i -> solve(i * 1_000L) }
        // Trims 1_000, 2_000 (best 2) and 49_000, 50_000 (worst 2).
        // Mean of 3_000..48_000 (46 solves) = (3_000 + 48_000) / 2 = 25_500
        assertEquals(25_500L, AverageCalculator.averageOfN(solves, 50))
    }

    @Test
    fun averageOfN_ao50_allowsUpToTwoDnfs() {
        val solves = (1..48).map { i -> solve(i * 1_000L) } + listOf(
            solve(0L, Penalty.DNF),
            solve(0L, Penalty.DNF)
        )
        // Trims 1_000, 2_000 (best 2) and 2 DNFs (worst 2).
        // Mean of remaining 46 solves = 25_500
        assertEquals(25_500L, AverageCalculator.averageOfN(solves, 50))
    }

    @Test
    fun averageOfN_ao50_returnsNullForThreeDnfs() {
        val solves = (1..47).map { i -> solve(i * 1_000L) } + listOf(
            solve(0L, Penalty.DNF),
            solve(0L, Penalty.DNF),
            solve(0L, Penalty.DNF)
        )
        // 3 DNFs exceeds trimCount of 2 -> null
        assertNull(AverageCalculator.averageOfN(solves, 50))
    }

    @Test
    fun averageOfN_ao100_trimsFiveBestAndWorst() {
        // Ao100 trimCount = (100 * 0.05).toInt() = 5
        val solves = (1..100).map { i -> solve(i * 1_000L) }
        // Trims 1_000..5_000 and 96_000..100_000.
        // Mean of remaining 90 solves (6_000..95_000) = (6_000 + 95_000) / 2 = 50_500
        assertEquals(50_500L, AverageCalculator.averageOfN(solves, 100))
    }

    @Test
    fun averageOfN_ao100_allowsUpToFiveDnfs() {
        val solves = (1..95).map { i -> solve(i * 1_000L) } + (1..5).map { solve(0L, Penalty.DNF) }
        // Trims 5 best and 5 DNFs as worst.
        // Mean of remaining 90 solves = 50_500
        assertEquals(50_500L, AverageCalculator.averageOfN(solves, 100))
    }

    @Test
    fun averageOfN_ao100_returnsNullForSixDnfs() {
        val solves = (1..94).map { i -> solve(i * 1_000L) } + (1..6).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageOfN(solves, 100))
    }

    @Test
    fun averageOfN_ao500_allowsUpTo25Dnfs() {
        // Ao500 trimCount = (500 * 0.05).toInt() = 25
        val solvesWith25Dnf = (1..475).map { i -> solve(i * 1_000L) } + (1..25).map { solve(0L, Penalty.DNF) }
        org.junit.Assert.assertNotNull(AverageCalculator.averageOfN(solvesWith25Dnf, 500))

        val solvesWith26Dnf = (1..474).map { i -> solve(i * 1_000L) } + (1..26).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageOfN(solvesWith26Dnf, 500))
    }

    @Test
    fun averageOfN_ao1000_allowsUpTo50Dnfs() {
        // Ao1000 trimCount = (1000 * 0.05).toInt() = 50
        val solvesWith50Dnf = (1..950).map { i -> solve(i * 1_000L) } + (1..50).map { solve(0L, Penalty.DNF) }
        org.junit.Assert.assertNotNull(AverageCalculator.averageOfN(solvesWith50Dnf, 1000))

        val solvesWith51Dnf = (1..949).map { i -> solve(i * 1_000L) } + (1..51).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageOfN(solvesWith51Dnf, 1000))
    }

    @Test
    fun averageOfN_ao2000_allowsUpTo100Dnfs() {
        // Ao2000 trimCount = (2000 * 0.05).toInt() = 100
        val solvesWith100Dnf = (1..1900).map { i -> solve(i * 1_000L) } + (1..100).map { solve(0L, Penalty.DNF) }
        org.junit.Assert.assertNotNull(AverageCalculator.averageOfN(solvesWith100Dnf, 2000))

        val solvesWith101Dnf = (1..1899).map { i -> solve(i * 1_000L) } + (1..101).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageOfN(solvesWith101Dnf, 2000))
    }

    @Test
    fun bestAverageOfN_skipsDnfWindowsAndFindsBest() {
        // 60 solves: first 50 has 3 DNFs at start (invalid Ao50 for start=0), last 50 has 0 DNFs (valid Ao50)
        val initialDnfs = (1..3).map { solve(0L, Penalty.DNF) }
        val validSolves = (1..57).map { i -> solve(i * 1_000L) }
        val allSolves = initialDnfs + validSolves

        val best = AverageCalculator.bestAverageOfN(allSolves, 50)
        org.junit.Assert.assertNotNull(best)
    }

    private fun solve(timeInMillis: Long, penalty: Penalty = Penalty.NONE): SolveTime {
        return SolveTime(timeInMillis = timeInMillis, penalty = penalty)
    }
}
