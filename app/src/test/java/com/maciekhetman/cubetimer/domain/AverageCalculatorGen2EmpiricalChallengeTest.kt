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
 * Empirical challenger test suite verifying AverageCalculator.averageWindow math,
 * edge cases, and window boundaries as required by Milestone 2 verification tasks.
 */
class AverageCalculatorGen2EmpiricalChallengeTest {

    private fun solve(timeInMillis: Long, penalty: Penalty = Penalty.NONE): SolveTime {
        return SolveTime(timeInMillis = timeInMillis, penalty = penalty)
    }

    private fun oracleAverage(window: List<SolveTime>): Long? {
        if (window.isEmpty()) return null
        val trimCount = when {
            window.size < 5 -> 0
            window.size < 20 -> 1
            else -> (window.size * 0.05).toInt()
        }

        val validSolves = window.filter { it.penalty != Penalty.DNF }.map { it.displayTime }.sorted()
        val dnfCount = window.size - validSolves.size
        if (dnfCount > trimCount) return null

        val trimmed = validSolves.drop(trimCount).dropLast(trimCount - dnfCount)
        if (trimmed.isEmpty()) return null
        return trimmed.average().toLong()
    }

    // =========================================================================
    // 1. SPECIFIED WINDOW SIZES: 3, 4, 5, 12, 19, 20, 50, 100, 500, 1000, 2000
    // =========================================================================

    @Test
    fun testWindowSize3_allDnfScenarios() {
        val size = 3
        val trimCount = 0

        // 0 DNF: mean of 3
        val clean = listOf(solve(10_000L), solve(11_000L), solve(12_000L))
        assertEquals(11_000L, AverageCalculator.averageWindow(clean))
        assertEquals(oracleAverage(clean), AverageCalculator.averageWindow(clean))

        // DNF count = trimCount + 1 (1 DNF) -> null
        val oneDnf = listOf(solve(10_000L), solve(11_000L), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(oneDnf))

        // All DNFs -> null
        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))

        // +2 penalty: 9s+2s (11s), 10s, 12s -> mean = (11 + 10 + 12) / 3 = 11s (11000ms)
        val withPlusTwo = listOf(solve(9_000L, Penalty.PLUS_TWO), solve(10_000L), solve(12_000L))
        assertEquals(11_000L, AverageCalculator.averageWindow(withPlusTwo))
        assertEquals(oracleAverage(withPlusTwo), AverageCalculator.averageWindow(withPlusTwo))
    }

    @Test
    fun testWindowSize4_allDnfScenarios() {
        val size = 4
        val trimCount = 0

        // 0 DNF: mean of 4
        val clean = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(13_000L))
        assertEquals(11_500L, AverageCalculator.averageWindow(clean))
        assertEquals(oracleAverage(clean), AverageCalculator.averageWindow(clean))

        // DNF count = trimCount + 1 (1 DNF) -> null
        val oneDnf = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(oneDnf))

        // All DNFs -> null
        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))

        // +2 penalty
        val withPlusTwo = listOf(solve(8_000L, Penalty.PLUS_TWO), solve(10_000L), solve(12_000L), solve(14_000L))
        assertEquals(11_500L, AverageCalculator.averageWindow(withPlusTwo))
    }

    @Test
    fun testWindowSize5_allDnfScenarios() {
        val size = 5
        val trimCount = 1

        // 0 DNF: drops best (10k) and worst (14k), averages middle 3 (11k, 12k, 13k) -> 12000L
        val clean = (0 until size).map { solve(10_000L + it * 1_000L) }
        assertEquals(12_000L, AverageCalculator.averageWindow(clean))
        assertEquals(oracleAverage(clean), AverageCalculator.averageWindow(clean))

        // DNF count = trimCount (1 DNF): drops best (10k) and worst DNF; averages middle 3 (11k, 12k, 13k) -> 12000L
        val oneDnf = (0 until (size - 1)).map { solve(10_000L + it * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(12_000L, AverageCalculator.averageWindow(oneDnf))
        assertEquals(oracleAverage(oneDnf), AverageCalculator.averageWindow(oneDnf))

        // DNF count = trimCount + 1 (2 DNFs) -> null
        val twoDnfs = (0 until (size - 2)).map { solve(10_000L + it * 1_000L) } + listOf(
            solve(0L, Penalty.DNF),
            solve(0L, Penalty.DNF)
        )
        assertNull(AverageCalculator.averageWindow(twoDnfs))

        // All DNFs -> null
        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))

        // +2 penalty: 9s (+2 -> 11s), 10s, 12s, 13s, 15s.
        // Sorted display times: 10s, 11s, 12s, 13s, 15s.
        // Trim drops 10s and 15s. Remaining: 11s, 12s, 13s -> average 12000ms
        val withPlusTwo = listOf(
            solve(10_000L),
            solve(9_000L, Penalty.PLUS_TWO),
            solve(12_000L),
            solve(13_000L),
            solve(15_000L)
        )
        assertEquals(12_000L, AverageCalculator.averageWindow(withPlusTwo))
        assertEquals(oracleAverage(withPlusTwo), AverageCalculator.averageWindow(withPlusTwo))
    }

    @Test
    fun testWindowSize12_allDnfScenarios() {
        val size = 12
        val trimCount = 1

        // 0 DNF: 12 solves 10k..21k. Trim drops 10k and 21k. Mean of 11k..20k = 15500L
        val clean = (0 until size).map { solve(10_000L + it * 1_000L) }
        assertEquals(15_500L, AverageCalculator.averageWindow(clean))
        assertEquals(oracleAverage(clean), AverageCalculator.averageWindow(clean))

        // DNF count = trimCount (1 DNF) -> drops best and DNF -> 15500L
        val oneDnf = (0 until (size - 1)).map { solve(10_000L + it * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(15_500L, AverageCalculator.averageWindow(oneDnf))
        assertEquals(oracleAverage(oneDnf), AverageCalculator.averageWindow(oneDnf))

        // DNF count = trimCount + 1 (2 DNFs) -> null
        val twoDnfs = (0 until (size - 2)).map { solve(10_000L + it * 1_000L) } + listOf(
            solve(0L, Penalty.DNF),
            solve(0L, Penalty.DNF)
        )
        assertNull(AverageCalculator.averageWindow(twoDnfs))

        // All DNFs -> null
        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))

        // Multiple +2 penalties
        val withPlusTwo = (0 until 10).map { solve(11_000L + it * 1_000L) } + listOf(
            solve(8_000L, Penalty.PLUS_TWO),  // 10_000L (best -> trimmed)
            solve(19_000L, Penalty.PLUS_TWO) // 21_000L (worst -> trimmed)
        )
        // Trimmed: 10_000 and 21_000. Remaining: 11_000..20_000 -> 15_500L
        assertEquals(15_500L, AverageCalculator.averageWindow(withPlusTwo))
        assertEquals(oracleAverage(withPlusTwo), AverageCalculator.averageWindow(withPlusTwo))
    }

    @Test
    fun testWindowSize19_allDnfScenarios() {
        val size = 19
        val trimCount = 1

        val clean = (1..size).map { solve(it * 1_000L) }
        // Trims 1000 and 19000. Mean of 2000..18000 = 10000L
        assertEquals(10_000L, AverageCalculator.averageWindow(clean))

        val oneDnf = (1 until size).map { solve(it * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(10_000L, AverageCalculator.averageWindow(oneDnf))

        val twoDnfs = (1 until (size - 1)).map { solve(it * 1_000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(twoDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    @Test
    fun testWindowSize20_allDnfScenarios() {
        val size = 20
        val trimCount = 1 // (20 * 0.05).toInt() = 1

        val clean = (1..size).map { solve(it * 1_000L) }
        // Trims 1000 and 20000. Mean of 2000..19000 (18 values) = 10500L
        assertEquals(10_500L, AverageCalculator.averageWindow(clean))

        val oneDnf = (1 until size).map { solve(it * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(10_500L, AverageCalculator.averageWindow(oneDnf))

        val twoDnfs = (1 until (size - 1)).map { solve(it * 1_000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(twoDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    @Test
    fun testWindowSize50_allDnfScenarios() {
        val size = 50
        val trimCount = 2 // (50 * 0.05).toInt() = 2

        val clean = (1..size).map { solve(it * 1_000L) }
        // Trims 1000, 2000 (best 2) and 49000, 50000 (worst 2). Mean of 3000..48000 = 25500L
        assertEquals(25_500L, AverageCalculator.averageWindow(clean))

        // DNF = 1
        val oneDnf = (1 until size).map { solve(it * 1_000L) } + listOf(solve(0L, Penalty.DNF))
        assertEquals(25_500L, AverageCalculator.averageWindow(oneDnf))

        // DNF = trimCount (2)
        val twoDnfs = (1 until (size - 1)).map { solve(it * 1_000L) } + listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertEquals(25_500L, AverageCalculator.averageWindow(twoDnfs))

        // DNF = trimCount + 1 (3) -> null
        val threeDnfs = (1 until (size - 2)).map { solve(it * 1_000L) } + (1..3).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(threeDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    @Test
    fun testWindowSize100_allDnfScenarios() {
        val size = 100
        val trimCount = 5 // (100 * 0.05).toInt() = 5

        val clean = (1..size).map { solve(it * 1_000L) }
        // Trims 1000..5000 and 96000..100000. Mean of 6000..95000 = 50500L
        assertEquals(50_500L, AverageCalculator.averageWindow(clean))

        // DNF = trimCount (5)
        val fiveDnfs = (1..(size - 5)).map { solve(it * 1_000L) } + (1..5).map { solve(0L, Penalty.DNF) }
        assertEquals(50_500L, AverageCalculator.averageWindow(fiveDnfs))

        // DNF = trimCount + 1 (6) -> null
        val sixDnfs = (1..(size - 6)).map { solve(it * 1_000L) } + (1..6).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(sixDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    @Test
    fun testWindowSize500_allDnfScenarios() {
        val size = 500
        val trimCount = 25 // (500 * 0.05).toInt() = 25

        val clean = (1..size).map { solve(20_000L) }
        assertEquals(20_000L, AverageCalculator.averageWindow(clean))

        // DNF = trimCount (25)
        val twentyFiveDnfs = (1..(size - 25)).map { solve(20_000L) } + (1..25).map { solve(0L, Penalty.DNF) }
        assertEquals(20_000L, AverageCalculator.averageWindow(twentyFiveDnfs))

        // DNF = trimCount + 1 (26) -> null
        val twentySixDnfs = (1..(size - 26)).map { solve(20_000L) } + (1..26).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(twentySixDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    @Test
    fun testWindowSize1000_allDnfScenarios() {
        val size = 1000
        val trimCount = 50 // (1000 * 0.05).toInt() = 50

        val clean = (1..size).map { solve(15_000L) }
        assertEquals(15_000L, AverageCalculator.averageWindow(clean))

        // DNF = trimCount (50)
        val fiftyDnfs = (1..(size - 50)).map { solve(15_000L) } + (1..50).map { solve(0L, Penalty.DNF) }
        assertEquals(15_000L, AverageCalculator.averageWindow(fiftyDnfs))

        // DNF = trimCount + 1 (51) -> null
        val fiftyOneDnfs = (1..(size - 51)).map { solve(15_000L) } + (1..51).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(fiftyOneDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    @Test
    fun testWindowSize2000_allDnfScenarios() {
        val size = 2000
        val trimCount = 100 // (2000 * 0.05).toInt() = 100

        val clean = (1..size).map { solve(14_000L) }
        assertEquals(14_000L, AverageCalculator.averageWindow(clean))

        // DNF = trimCount (100)
        val hundredDnfs = (1..(size - 100)).map { solve(14_000L) } + (1..100).map { solve(0L, Penalty.DNF) }
        assertEquals(14_000L, AverageCalculator.averageWindow(hundredDnfs))

        // DNF = trimCount + 1 (101) -> null
        val hundredOneDnfs = (1..(size - 101)).map { solve(14_000L) } + (1..101).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(hundredOneDnfs))

        val allDnfs = (1..size).map { solve(0L, Penalty.DNF) }
        assertNull(AverageCalculator.averageWindow(allDnfs))
    }

    // =========================================================================
    // 2. EXTREME EDGE CASES & STRESS HARNESS
    // =========================================================================

    @Test
    fun testEmptyWindowReturnsNull() {
        assertNull(AverageCalculator.averageWindow(emptyList()))
        assertNull(AverageCalculator.averageOfN(emptyList(), 5))
        assertNull(AverageCalculator.bestAverageOfN(emptyList(), 5))
    }

    @Test
    fun testWindowSize1() {
        // Size 1: 0 trim -> returns time
        val solveClean = listOf(solve(12_345L))
        assertEquals(12_345L, AverageCalculator.averageWindow(solveClean))

        val solvePlusTwo = listOf(solve(12_345L, Penalty.PLUS_TWO))
        assertEquals(14_345L, AverageCalculator.averageWindow(solvePlusTwo))

        val solveDnf = listOf(solve(12_345L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(solveDnf))
    }

    @Test
    fun testWindowSize2() {
        // Size 2: 0 trim -> mean
        val clean = listOf(solve(10_000L), solve(20_000L))
        assertEquals(15_000L, AverageCalculator.averageWindow(clean))

        val oneDnf = listOf(solve(10_000L), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(oneDnf))

        val twoDnfs = listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertNull(AverageCalculator.averageWindow(twoDnfs))
    }

    @Test
    fun testLargeSolveTimesNoOverflow() {
        // 100 solves each of 1 hour (3_600_000 ms)
        // Sum would be 360,000,000 ms, safely fits in Long and Double
        val solves = (1..100).map { solve(3_600_000L) }
        assertEquals(3_600_000L, AverageCalculator.averageWindow(solves))
        assertEquals(3_600_000L, AverageCalculator.averageOfN(solves, 100))
        assertEquals(3_600_000L, AverageCalculator.bestAverageOfN(solves, 100))
    }

    @Test
    fun testUnsortedInputIsSortedProperly() {
        // Window of 5 shuffled solves
        val ordered = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(13_000L), solve(14_000L))
        val shuffled = listOf(solve(13_000L), solve(10_000L), solve(14_000L), solve(11_000L), solve(12_000L))
        assertEquals(AverageCalculator.averageWindow(ordered), AverageCalculator.averageWindow(shuffled))
    }

    @Test
    fun testDnfPositionDoesNotAffectOutcome() {
        // DNF at beginning vs middle vs end
        val dnfAtStart = listOf(solve(0L, Penalty.DNF), solve(10_000L), solve(11_000L), solve(12_000L), solve(13_000L))
        val dnfInMiddle = listOf(solve(10_000L), solve(11_000L), solve(0L, Penalty.DNF), solve(12_000L), solve(13_000L))
        val dnfAtEnd = listOf(solve(10_000L), solve(11_000L), solve(12_000L), solve(13_000L), solve(0L, Penalty.DNF))

        val expected = 12_000L // best 10k and DNF dropped; 11k, 12k, 13k averaged
        assertEquals(expected, AverageCalculator.averageWindow(dnfAtStart))
        assertEquals(expected, AverageCalculator.averageWindow(dnfInMiddle))
        assertEquals(expected, AverageCalculator.averageWindow(dnfAtEnd))
    }

    @Test
    fun testMeanAndStandardDeviationBoundaryConditions() {
        // 0 solves
        assertEquals(0L, AverageCalculator.mean(emptyList()))
        assertEquals(0L, AverageCalculator.average(emptyList()))
        assertEquals(0.0, AverageCalculator.standardDeviation(emptyList()), 0.0001)

        // All DNF
        val allDnf = listOf(solve(0L, Penalty.DNF), solve(0L, Penalty.DNF))
        assertEquals(0L, AverageCalculator.mean(allDnf))
        assertEquals(0.0, AverageCalculator.standardDeviation(allDnf), 0.0001)

        // 1 valid solve
        val oneSolve = listOf(solve(12_000L))
        assertEquals(12_000L, AverageCalculator.mean(oneSolve))
        assertEquals(0.0, AverageCalculator.standardDeviation(oneSolve), 0.0001)

        // 2 identical solves
        val identical = listOf(solve(10_000L), solve(10_000L))
        assertEquals(10_000L, AverageCalculator.mean(identical))
        assertEquals(0.0, AverageCalculator.standardDeviation(identical), 0.0001)
    }

    @Test
    fun testRandomizedOracleFuzzingAcrossSizes() {
        val rng = Random(12345)
        val windowSizes = listOf(3, 4, 5, 7, 12, 19, 20, 30, 50, 75, 100, 200)

        for (size in windowSizes) {
            val trimCount = when {
                size < 5 -> 0
                size < 20 -> 1
                else -> (size * 0.05).toInt()
            }

            repeat(20) {
                // Generate random solves
                val dnfCount = rng.nextInt(0, (trimCount + 3).coerceAtMost(size + 1))
                val validCount = (size - dnfCount).coerceAtLeast(0)

                val validSolves = (0 until validCount).map {
                    val raw = rng.nextLong(6_000L, 60_000L)
                    val penalty = when (rng.nextInt(5)) {
                        0 -> Penalty.PLUS_TWO
                        else -> Penalty.NONE
                    }
                    solve(raw, penalty)
                }
                val dnfSolves = (0 until (size - validCount)).map {
                    solve(0L, Penalty.DNF)
                }
                val window = (validSolves + dnfSolves).shuffled(rng)

                val expected = oracleAverage(window)
                val actual = AverageCalculator.averageWindow(window)
                assertEquals("Mismatch for size=$size with dnfCount=$dnfCount", expected, actual)
            }
        }
    }
}
