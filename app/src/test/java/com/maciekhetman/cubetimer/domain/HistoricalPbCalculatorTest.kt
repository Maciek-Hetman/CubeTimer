package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalPbCalculatorTest {

    @Test
    fun `first solve of an event is always PB`() {
        val result = HistoricalPbCalculator.calculate(
            durationMs = 15230L,
            penalty = Penalty.NONE,
            priorBestDurationMs = null
        )

        assertTrue(result.isPb)
        assertNull(result.priorBestDurationMs)
        assertNull(result.deltaMs)
    }

    @Test
    fun `solve beating prior best calculates delta`() {
        // Prior best: 12.40s (12400ms)
        // New solve: 11.55s (11550ms) -> improvement of 0.85s (850ms)
        val result = HistoricalPbCalculator.calculate(
            durationMs = 11550L,
            penalty = Penalty.NONE,
            priorBestDurationMs = 12400L
        )

        assertTrue(result.isPb)
        assertEquals(12400L, result.priorBestDurationMs)
        assertEquals(850L, result.deltaMs)
    }

    @Test
    fun `solve with plus two penalty adds two seconds before comparing to prior best`() {
        // Raw duration: 9.50s (9500ms) + 2s = 11.50s (11500ms)
        // Prior best: 12.00s (12000ms) -> delta 500ms (0.50s)
        val result = HistoricalPbCalculator.calculate(
            durationMs = 9500L,
            penalty = Penalty.PLUS_TWO,
            priorBestDurationMs = 12000L
        )

        assertTrue(result.isPb)
        assertEquals(12000L, result.priorBestDurationMs)
        assertEquals(500L, result.deltaMs)

        // But if raw 10.50s + 2s = 12.50s, which is slower than 12.00s
        val slowerResult = HistoricalPbCalculator.calculate(
            durationMs = 10500L,
            penalty = Penalty.PLUS_TWO,
            priorBestDurationMs = 12000L
        )
        assertFalse(slowerResult.isPb)
        assertNull(slowerResult.deltaMs)
    }

    @Test
    fun `tied solve is not considered a new personal best`() {
        val result = HistoricalPbCalculator.calculate(
            durationMs = 12400L,
            penalty = Penalty.NONE,
            priorBestDurationMs = 12400L
        )

        assertFalse(result.isPb)
        assertEquals(12400L, result.priorBestDurationMs)
        assertNull(result.deltaMs)
    }

    @Test
    fun `slower solve is not considered a personal best`() {
        val result = HistoricalPbCalculator.calculate(
            durationMs = 14500L,
            penalty = Penalty.NONE,
            priorBestDurationMs = 12400L
        )

        assertFalse(result.isPb)
        assertEquals(12400L, result.priorBestDurationMs)
        assertNull(result.deltaMs)
    }

    @Test
    fun `dnf solve is never a personal best even if duration is faster`() {
        val resultWithPrior = HistoricalPbCalculator.calculate(
            durationMs = 8000L,
            penalty = Penalty.DNF,
            priorBestDurationMs = 12400L
        )

        assertFalse(resultWithPrior.isPb)
        assertNull(resultWithPrior.deltaMs)

        val resultFirstSolve = HistoricalPbCalculator.calculate(
            durationMs = 8000L,
            penalty = Penalty.DNF,
            priorBestDurationMs = null
        )

        assertFalse(resultFirstSolve.isPb)
        assertNull(resultFirstSolve.deltaMs)
    }

    @Test
    fun `calculate overload accepting SolveTime works as expected`() {
        val solve = SolveTime(
            id = "solve-1",
            timeInMillis = 9420L,
            penalty = Penalty.NONE,
            timestamp = System.currentTimeMillis(),
            scramble = "R U R' U'",
            mode = Mode.CUBE_3x3
        )

        val result = HistoricalPbCalculator.calculate(solve, 10000L)

        assertTrue(result.isPb)
        assertEquals(10000L, result.priorBestDurationMs)
        assertEquals(580L, result.deltaMs)
    }
}
