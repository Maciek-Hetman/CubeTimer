package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Verifies that the optimized (sliding-window / Fenwick-tree) `AverageCalculator.bestAverageOfN`
 * produces results identical to the naive O(n * count * log count) definition — take the min of
 * `averageWindow(solves.subList(start, start + count))` over every valid `start` — for seeded
 * random data that mixes clean solves, +2 penalties and DNFs, across a range of window sizes and
 * list lengths (including lists shorter than `count` and lists exactly `count` long).
 */
class AverageCalculatorBestAverageOfNOptimizationTest {

    private fun solve(timeInMillis: Long, penalty: Penalty = Penalty.NONE): SolveTime {
        return SolveTime(timeInMillis = timeInMillis, penalty = penalty)
    }

    /** Naive reference definition, built only from the unmodified `averageWindow`. */
    private fun naiveBestAverageOfN(solves: List<SolveTime>, count: Int): Long? {
        if (solves.size < count) return null
        return (0..(solves.size - count))
            .mapNotNull { start -> AverageCalculator.averageWindow(solves.subList(start, start + count)) }
            .minOrNull()
    }

    private fun randomSolves(random: Random, size: Int): List<SolveTime> {
        return (0 until size).map {
            when (random.nextInt(10)) {
                0 -> solve(0L, Penalty.DNF) // ~10% DNF
                1 -> solve(random.nextLong(3_000L, 60_000L), Penalty.PLUS_TWO) // ~10% +2
                else -> solve(random.nextLong(3_000L, 60_000L))
            }
        }
    }

    @Test
    fun bestAverageOfN_matchesNaiveReference_acrossSeededRandomData() {
        val random = Random(42)
        val counts = listOf(5, 12, 50, 100, 500)

        for (count in counts) {
            // Sizes below, exactly at, and well above `count`, including a couple of edge offsets.
            val sizes = listOf(0, 1, count - 1, count, count + 1, count + 7, count * 2, count * 3 + 13)
                .filter { it >= 0 }
                .distinct()

            for (size in sizes) {
                repeat(5) { trial ->
                    val solves = randomSolves(random, size)
                    val expected = naiveBestAverageOfN(solves, count)
                    val actual = AverageCalculator.bestAverageOfN(solves, count)
                    assertEquals(
                        "count=$count size=$size trial=$trial mismatch",
                        expected,
                        actual
                    )
                }
            }
        }
    }

    @Test
    fun bestAverageOfN_matchesNaiveReference_withHeavyDnfClustering() {
        // Bias much more heavily towards DNFs so many windows sit right at (or just past) the
        // dnfCount > trimCount boundary, which is the trickiest part of the sliding-window state.
        val random = Random(1234)
        val counts = listOf(5, 12, 50, 100)

        for (count in counts) {
            repeat(8) { trial ->
                val size = count + random.nextInt(40)
                val solves = (0 until size).map {
                    when (random.nextInt(3)) {
                        0 -> solve(0L, Penalty.DNF)
                        1 -> solve(random.nextLong(3_000L, 60_000L), Penalty.PLUS_TWO)
                        else -> solve(random.nextLong(3_000L, 60_000L))
                    }
                }
                val expected = naiveBestAverageOfN(solves, count)
                val actual = AverageCalculator.bestAverageOfN(solves, count)
                assertEquals("count=$count trial=$trial mismatch", expected, actual)
            }
        }
    }

    @Test
    fun bestAverageOfN_matchesNaiveReference_withManyDuplicateTimes() {
        // Duplicate display times exercise the Fenwick tree's tie-breaking (multiple solves
        // sharing the same coordinate-compressed rank).
        val random = Random(99)
        val counts = listOf(5, 12, 50)
        val pool = listOf(10_000L, 10_000L, 12_000L, 12_000L, 15_000L, 0L)

        for (count in counts) {
            repeat(6) { trial ->
                val size = count + random.nextInt(30)
                val solves = (0 until size).map {
                    val time = pool[random.nextInt(pool.size)]
                    if (time == 0L && random.nextBoolean()) solve(0L, Penalty.DNF) else solve(time)
                }
                val expected = naiveBestAverageOfN(solves, count)
                val actual = AverageCalculator.bestAverageOfN(solves, count)
                assertEquals("count=$count trial=$trial mismatch", expected, actual)
            }
        }
    }
}
