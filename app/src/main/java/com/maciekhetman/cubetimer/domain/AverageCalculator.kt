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

    /**
     * Equivalent to (but far cheaper than) taking the min of
     * `averageWindow(solves.subList(start, start + count))` over every `start`.
     *
     * Every window here has the same fixed size (`count`), so `trimCount` is constant across
     * all windows (see `averageWindow`). That lets us maintain the window's valid (non-DNF)
     * times in a Fenwick tree (order statistics: prefix count + prefix sum over coordinate-
     * compressed values) while sliding the window one solve at a time, turning what was an
     * O(n * count * log count) scan (sort every window from scratch) into O(n log n).
     */
    fun bestAverageOfN(solves: List<SolveTime>, count: Int): Long? {
        if (solves.size < count) return null
        if (count <= 0) return null

        val trimCount = when {
            count < 5 -> 0
            count < 20 -> 1
            else -> (count * 0.05).toInt()
        }

        // Coordinate-compress the distinct display times of non-DNF solves so they can be used
        // as 1-based Fenwick tree indices.
        val sortedTimes = solves.asSequence()
            .filter { it.penalty != Penalty.DNF }
            .map { it.displayTime }
            .distinct()
            .sorted()
            .toList()
        val rankCount = sortedTimes.size

        val fenwickCount = LongArray(rankCount + 1)
        val fenwickSum = LongArray(rankCount + 1)

        fun rankOf(value: Long): Int {
            var lo = 0
            var hi = rankCount - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sortedTimes[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo + 1
        }

        fun fenwickAdd(rank: Int, deltaCount: Long, deltaSum: Long) {
            var i = rank
            while (i <= rankCount) {
                fenwickCount[i] += deltaCount
                fenwickSum[i] += deltaSum
                i += i and (-i)
            }
        }

        fun prefixCount(rank: Int): Long {
            var i = rank
            var total = 0L
            while (i > 0) {
                total += fenwickCount[i]
                i -= i and (-i)
            }
            return total
        }

        fun prefixSum(rank: Int): Long {
            var i = rank
            var total = 0L
            while (i > 0) {
                total += fenwickSum[i]
                i -= i and (-i)
            }
            return total
        }

        // Smallest rank r such that prefixCount(r) >= k (1-indexed k-th smallest value's rank).
        fun kthSmallestRank(k: Long): Int {
            var lo = 1
            var hi = rankCount
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (prefixCount(mid) >= k) hi = mid else lo = mid + 1
            }
            return lo
        }

        var windowValidSum = 0L
        var windowValidCount = 0
        var windowDnfCount = 0

        fun addToWindow(solve: SolveTime) {
            if (solve.penalty == Penalty.DNF) {
                windowDnfCount++
            } else {
                val rank = rankOf(solve.displayTime)
                fenwickAdd(rank, 1L, solve.displayTime)
                windowValidSum += solve.displayTime
                windowValidCount++
            }
        }

        fun removeFromWindow(solve: SolveTime) {
            if (solve.penalty == Penalty.DNF) {
                windowDnfCount--
            } else {
                val rank = rankOf(solve.displayTime)
                fenwickAdd(rank, -1L, -solve.displayTime)
                windowValidSum -= solve.displayTime
                windowValidCount--
            }
        }

        // Sum of the `k` smallest valid times currently in the window.
        fun sumOfSmallest(k: Int): Long {
            if (k <= 0) return 0L
            if (k >= windowValidCount) return windowValidSum
            val rank = kthSmallestRank(k.toLong())
            val countAtRank = prefixCount(rank)
            val sumUpToRank = prefixSum(rank)
            val extra = countAtRank - k
            return if (extra <= 0) sumUpToRank else sumUpToRank - extra * sortedTimes[rank - 1]
        }

        fun currentWindowAverage(): Long? {
            if (windowDnfCount > trimCount) return null
            val dropBest = trimCount
            val dropWorst = trimCount - windowDnfCount
            val remaining = windowValidCount - dropBest - dropWorst
            if (remaining <= 0) return null

            val droppedBestSum = sumOfSmallest(dropBest)
            val droppedWorstSum = windowValidSum - sumOfSmallest(windowValidCount - dropWorst)
            val remainingSum = windowValidSum - droppedBestSum - droppedWorstSum
            return (remainingSum.toDouble() / remaining).toLong()
        }

        for (i in 0 until count) addToWindow(solves[i])
        var best = currentWindowAverage()

        for (start in 1..(solves.size - count)) {
            removeFromWindow(solves[start - 1])
            addToWindow(solves[start + count - 1])
            val candidate = currentWindowAverage()
            if (candidate != null && (best == null || candidate < best)) {
                best = candidate
            }
        }

        return best
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

    fun averageWindow(window: List<SolveTime>): Long? {
        if (window.isEmpty()) return null

        val trimCount = when {
            window.size < 5 -> 0
            window.size < 20 -> 1
            else -> (window.size * 0.05).toInt()
        }

        val validTimes = window
            .filter { it.penalty != Penalty.DNF }
            .map { it.displayTime }
            .sorted()
        val dnfCount = window.size - validTimes.size

        if (dnfCount > trimCount) return null

        val trimmedTimes = validTimes
            .drop(trimCount)
            .dropLast(trimCount - dnfCount)

        return trimmedTimes.takeIf { it.isNotEmpty() }?.average()?.toLong()
    }
}
