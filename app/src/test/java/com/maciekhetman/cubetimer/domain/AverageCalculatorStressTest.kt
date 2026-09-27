package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Empirical challenger stress harness verifying window boundary conditions,
 * proportional 5% trimming, and DNF thresholds across all standard and extended window sizes.
 */
class AverageCalculatorStressTest {

    private fun solve(timeInMillis: Long, penalty: Penalty = Penalty.NONE): SolveTime {
        return SolveTime(timeInMillis = timeInMillis, penalty = penalty)
    }

    /**
     * Independent reference oracle for mathematical verification.
     */
    private fun referenceAverageOracle(window: List<SolveTime>): Long? {
        if (window.isEmpty()) return null
        val size = window.size
        val trimCount = when {
            size < 5 -> 0
            size < 20 -> 1
            else -> (size * 0.05).toInt()
        }
        val validSolves = window.filter { it.penalty != Penalty.DNF }.map { it.displayTime }.sorted()
        val dnfCount = window.size - validSolves.size
        if (dnfCount > trimCount) return null

        val trimmed = validSolves.drop(trimCount).dropLast(trimCount - dnfCount)
        if (trimmed.isEmpty()) return null
        return (trimmed.sum().toDouble() / trimmed.size).toLong()
    }

    // =========================================================================
    // 1. WINDOW BOUNDARY CONDITIONS: 0, 1, 2, 3, 4, 5, 12, 19, 20, 50, 100, 500, 1000, 2000
    // =========================================================================

    @Test
    fun boundary_windowSize0_returnsNull() {
        assertNull(AverageCalculator.averageWindow(emptyList()))
        assertNull(AverageCalculator.averageOfN(emptyList(), 0))
    }

    @Test
    fun boundary_windowSize1_zeroDnf_returnsTime() {
        val solves = listOf(solve(12_345L))
        assertEquals(12_345L, AverageCalculator.averageWindow(solves))
        assertEquals(referenceAverageOracle(solves), AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize1_oneDnf_returnsNull() {
        val solves = listOf(solve(12_345L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize2_zeroDnf_returnsMean() {
        val solves = listOf(solve(10_000L), solve(12_000L))
        assertEquals(11_000L, AverageCalculator.averageWindow(solves))
        assertEquals(referenceAverageOracle(solves), AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize2_oneDnf_returnsNull() {
        val solves = listOf(solve(10_000L), solve(12_000L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize3_zeroDnf_returnsMean() {
        val solves = listOf(solve(10_000L), solve(11_000L), solve(15_000L))
        assertEquals(12_000L, AverageCalculator.averageWindow(solves))
        assertEquals(referenceAverageOracle(solves), AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize3_oneDnf_returnsNull() {
        val solves = listOf(solve(10_000L), solve(11_000L), solve(15_000L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize4_zeroDnf_returnsMean() {
        val solves = listOf(solve(10_000L), solve(12_000L), solve(14_000L), solve(16_000L))
        assertEquals(13_000L, AverageCalculator.averageWindow(solves))
        assertEquals(referenceAverageOracle(solves), AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize4_oneDnf_returnsNull() {
        val solves = listOf(solve(10_000L), solve(12_000L), solve(14_000L), solve(16_000L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves))
    }

    @Test
    fun boundary_windowSize5_allows1Dnf_rejects2Dnfs() {
        // Ao5: trimCount = 1
        val solvesValid = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(13_000L), solve(14_000L))
        assertEquals(12_000L, AverageCalculator.averageWindow(solvesValid))
        assertEquals(referenceAverageOracle(solvesValid), AverageCalculator.averageWindow(solvesValid))

        val solves1Dnf = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(13_000L), solve(0L, Penalty.DNF))
        assertEquals(12_000L, AverageCalculator.averageWindow(solves1Dnf))
        assertEquals(referenceAverageOracle(solves1Dnf), AverageCalculator.averageWindow(solves1Dnf))

        val solves2Dnfs = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves2Dnfs))
    }

    @Test
    fun boundary_windowSize12_allows1Dnf_rejects2Dnfs() {
        // Ao12: trimCount = 1
        val baseTimes = (0 until 12).map { 10_000L + it * 1_000L }
        val solvesValid = baseTimes.map { solve(it) }
        // Trims 10_000 and 21_000; mean of 11_000..20_000 is 15_500
        assertEquals(15_500L, AverageCalculator.averageWindow(solvesValid))
        assertEquals(referenceAverageOracle(solvesValid), AverageCalculator.averageWindow(solvesValid))

        val solves1Dnf = (0 until 11).map { solve(10_000L + it * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(15_500L, AverageCalculator.averageWindow(solves1Dnf))
        assertEquals(referenceAverageOracle(solves1Dnf), AverageCalculator.averageWindow(solves1Dnf))

        val solves2Dnfs = (0 until 10).map { solve(10_000L + it * 1_000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves2Dnfs))
    }

    @Test
    fun boundary_windowSize19_allows1Dnf_rejects2Dnfs() {
        // Ao19: window.size < 20 -> trimCount = 1
        val solvesValid = (1..19).map { solve(it * 1000L) }
        // Trims 1_000 and 19_000; mean of 2_000..18_000 (17 values) = 10_000
        assertEquals(10_000L, AverageCalculator.averageWindow(solvesValid))
        assertEquals(referenceAverageOracle(solvesValid), AverageCalculator.averageWindow(solvesValid))

        val solves1Dnf = (1..18).map { solve(it * 1000L) } + listOf(solve(0L, Penalty.DNF))
        // Trims 1_000 and 1 DNF; mean of 2_000..18_000 = 10_000
        assertEquals(10_000L, AverageCalculator.averageWindow(solves1Dnf))
        assertEquals(referenceAverageOracle(solves1Dnf), AverageCalculator.averageWindow(solves1Dnf))

        val solves2Dnfs = (1..17).map { solve(it * 1000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves2Dnfs))
    }

    @Test
    fun boundary_windowSize20_allows1Dnf_rejects2Dnfs() {
        // Ao20: (20 * 0.05).toInt() = 1 -> trimCount = 1
        val solvesValid = (1..20).map { solve(it * 1000L) }
        // Trims 1_000 and 20_000; mean of 2_000..19_000 (18 values) = 10_500
        assertEquals(10_500L, AverageCalculator.averageWindow(solvesValid))
        assertEquals(referenceAverageOracle(solvesValid), AverageCalculator.averageWindow(solvesValid))

        val solves1Dnf = (1..19).map { solve(it * 1000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(10_500L, AverageCalculator.averageWindow(solves1Dnf))
        assertEquals(referenceAverageOracle(solves1Dnf), AverageCalculator.averageWindow(solves1Dnf))

        val solves2Dnfs = (1..18).map { solve(it * 1000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solves2Dnfs))
    }

    @Test
    fun boundary_windowSize50_allows2Dnfs_rejects3Dnfs() {
        // Ao50: (50 * 0.05).toInt() = 2 -> trimCount = 2
        val solvesValid = (1..50).map { solve(it * 1000L) }
        // Trims 1000, 2000 (best 2) and 49000, 50000 (worst 2).
        // Remaining: 3000..48000 -> mean = 25_500
        assertEquals(25_500L, AverageCalculator.averageWindow(solvesValid))
        assertEquals(referenceAverageOracle(solvesValid), AverageCalculator.averageWindow(solvesValid))

        val solves1Dnf = (1..49).map { solve(it * 1000L) } + listOf(solve(0L, Penalty.DNF))
        // Trims 1000, 2000 and 1 DNF + 49000 -> remaining 3000..48000 -> 25_500
        assertEquals(25_500L, AverageCalculator.averageWindow(solves1Dnf))
        assertEquals(referenceAverageOracle(solves1Dnf), AverageCalculator.averageWindow(solves1Dnf))

        val solves2Dnfs = (1..48).map { solve(it * 1000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        // Trims 1000, 2000 and 2 DNFs -> remaining 3000..48000 -> 25_500
        assertEquals(25_500L, AverageCalculator.averageWindow(solves2Dnfs))
        assertEquals(referenceAverageOracle(solves2Dnfs), AverageCalculator.averageWindow(solves2Dnfs))

        val solves3Dnfs = (1..47).map { solve(it * 1000L) } + (1..3).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(solves3Dnfs))
    }

    @Test
    fun boundary_windowSize100_allows5Dnfs_rejects6Dnfs() {
        // Ao100: (100 * 0.05).toInt() = 5 -> trimCount = 5
        val solvesValid = (1..100).map { solve(it * 1000L) }
        assertEquals(50_500L, AverageCalculator.averageWindow(solvesValid))
        assertEquals(referenceAverageOracle(solvesValid), AverageCalculator.averageWindow(solvesValid))

        for (dnfCount in 1..5) {
            val solves = (1..(100 - dnfCount)).map { solve(it * 1000L) } + (1..dnfCount).map { solve(0L, Penalty.DNF) }
            assertEquals(
                "Failed at dnfCount=$dnfCount for Ao100",
                50_500L,
                AverageCalculator.averageWindow(solves)
            )
            assertEquals(referenceAverageOracle(solves), AverageCalculator.averageWindow(solves))
        }

        val solves6Dnfs = (1..94).map { solve(it * 1000L) } + (1..6).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(solves6Dnfs))
    }

    @Test
    fun boundary_windowSize500_allows25Dnfs_rejects26Dnfs() {
        // Ao500: (500 * 0.05).toInt() = 25 -> trimCount = 25
        val solves25Dnfs = (1..475).map { solve(10_000L) } + (1..25).map { solve(0L, Penalty.DNF) }
        assertEquals(10_000L, AverageCalculator.averageWindow(solves25Dnfs))
        assertEquals(referenceAverageOracle(solves25Dnfs), AverageCalculator.averageWindow(solves25Dnfs))

        val solves26Dnfs = (1..474).map { solve(10_000L) } + (1..26).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(solves26Dnfs))
    }

    @Test
    fun boundary_windowSize1000_allows50Dnfs_rejects51Dnfs() {
        // Ao1000: (1000 * 0.05).toInt() = 50 -> trimCount = 50
        val solves50Dnfs = (1..950).map { solve(10_000L) } + (1..50).map { solve(0L, Penalty.DNF) }
        assertEquals(10_000L, AverageCalculator.averageWindow(solves50Dnfs))
        assertEquals(referenceAverageOracle(solves50Dnfs), AverageCalculator.averageWindow(solves50Dnfs))

        val solves51Dnfs = (1..949).map { solve(10_000L) } + (1..51).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(solves51Dnfs))
    }

    @Test
    fun boundary_windowSize2000_allows100Dnfs_rejects101Dnfs() {
        // Ao2000: (2000 * 0.05).toInt() = 100 -> trimCount = 100
        val solves100Dnfs = (1..1900).map { solve(10_000L) } + (1..100).map { solve(0L, Penalty.DNF) }
        assertEquals(10_000L, AverageCalculator.averageWindow(solves100Dnfs))
        assertEquals(referenceAverageOracle(solves100Dnfs), AverageCalculator.averageWindow(solves100Dnfs))

        val solves101Dnfs = (1..1899).map { solve(10_000L) } + (1..101).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(solves101Dnfs))
    }

    // =========================================================================
    // 2. RANDOMIZED PROPERTY-BASED TESTING AGAINST REFERENCE ORACLE
    // =========================================================================

    @Test
    fun propertyTest_randomizedWindows_matchReferenceOracle() {
        val rng = Random(42)
        val testedWindowSizes = listOf(1, 2, 3, 4, 5, 6, 12, 19, 20, 25, 50, 75, 100, 150, 200)

        for (size in testedWindowSizes) {
            val trimCount = when {
                size < 5 -> 0
                size < 20 -> 1
                else -> (size * 0.05).toInt()
            }

            // Test 10 variations per window size
            for (iter in 1..10) {
                // Random dnfCount up to trimCount + 2
                val maxDnfs = (trimCount + 2).coerceAtMost(size)
                val dnfCount = rng.nextInt(0, maxDnfs + 1)
                val validCount = size - dnfCount

                val validSolves = (0 until validCount).map {
                    val raw = rng.nextLong(7_000L, 35_000L)
                    val penalty = if (rng.nextInt(10) == 0) Penalty.PLUS_TWO else Penalty.NONE
                    solve(raw, penalty)
                }
                val dnfSolves = (0 until dnfCount).map {
                    solve(0L, Penalty.DNF)
                }

                val window = (validSolves + dnfSolves).shuffled(rng)
                val expected = referenceAverageOracle(window)
                val actual = AverageCalculator.averageWindow(window)

                if (dnfCount > trimCount) {
                    assertNull("Expected null when dnfCount=$dnfCount > trimCount=$trimCount for size=$size", actual)
                } else {
                    assertNotNull("Expected non-null for dnfCount=$dnfCount <= trimCount=$trimCount for size=$size", actual)
                    assertEquals("Oracle mismatch for size=$size, dnfCount=$dnfCount", expected, actual)
                }
            }
        }
    }

    // =========================================================================
    // 3. PLUS TWO PENALTY INCLUDED IN TRIMMING
    // =========================================================================

    @Test
    fun plusTwoPenalty_correctlyAddsTwoSecondsAndTrims() {
        // Ao5: solves of 10s, 10s, 10s, 10s, 9s (+2 -> 11s)
        // Solves sorted by displayTime: 10_000, 10_000, 10_000, 10_000, 11_000
        // Best (10_000) and worst (11_000) dropped -> mean of middle 3 is 10_000
        val solves = listOf(
            solve(10_000L),
            solve(10_000L),
            solve(10_000L),
            solve(10_000L),
            solve(9_000L, Penalty.PLUS_TWO)
        )
        assertEquals(10_000L, AverageCalculator.averageWindow(solves))
    }

    // =========================================================================
    // 4. BEST AVERAGE OF N WITH INTERSPERSED DNF WINDOWS
    // =========================================================================

    @Test
    fun bestAverageOfN_allWindowsDnf_returnsNull() {
        // 10 solves with Ao5, all sub-windows of 5 have at least 2 DNFs
        // E.g. alternating DNF and valid: DNF, 10s, DNF, 10s, DNF, 10s, DNF, 10s, DNF, 10s
        // Any 5 consecutive solves will have at least 2 or 3 DNFs -> all sub-windows are null
        val solves = (1..10).map { i ->
            if (i % 2 == 1) solve(0L, Penalty.DNF) else solve(10_000L)
        }
        assertNull(AverageCalculator.bestAverageOfN(solves, 5))
    }

    @Test
    fun bestAverageOfN_validWindowFound_returnsMinimum() {
        // Solves:
        // First 5: 3 DNFs -> invalid
        // Next 5: valid solves of 20_000L -> average 20_000L
        // Next 5: valid solves of 15_000L -> average 15_000L
        val invalidSolves = (1..5).map { solve(0L, Penalty.DNF) }
        val okSolves = (1..5).map { solve(20_000L) }
        val bestSolves = (1..5).map { solve(15_000L) }

        val allSolves = invalidSolves + okSolves + bestSolves
        assertEquals(15_000L, AverageCalculator.bestAverageOfN(allSolves, 5))
    }
}
