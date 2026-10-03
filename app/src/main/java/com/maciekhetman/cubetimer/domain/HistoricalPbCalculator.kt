package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime

/**
 * Encapsulates the result of a historical Personal Best (PB) single evaluation.
 *
 * @property isPb True if this solve was a Personal Best single at the time it occurred.
 * @property priorBestDurationMs Effective duration (ms) of the fastest prior non-DNF solve for this event,
 *                               or null if no prior valid solve exists (i.e. this was the first solve).
 * @property deltaMs Time difference in milliseconds (priorBest - effectiveDuration) if this solve was a PB
 *                   and a prior best existed; null otherwise.
 */
data class HistoricalPbResult(
    val isPb: Boolean,
    val priorBestDurationMs: Long?,
    val deltaMs: Long?
)

/**
 * Pure domain utility for calculating Historical Personal Best singles.
 */
object HistoricalPbCalculator {

    /**
     * Calculates whether a solve was a Personal Best single at the time it occurred.
     *
     * @param durationMs Raw solve duration in milliseconds.
     * @param penalty Penalty applied to the solve ([Penalty.NONE], [Penalty.PLUS_TWO], [Penalty.DNF]).
     * @param priorBestDurationMs Fastest prior effective duration (ms) for this event before this solve's timestamp.
     * @return [HistoricalPbResult] containing PB status, prior best duration, and delta.
     */
    fun calculate(
        durationMs: Long,
        penalty: Penalty,
        priorBestDurationMs: Long?
    ): HistoricalPbResult {
        // Special Case: DNF solve is NEVER a Personal Best
        if (penalty == Penalty.DNF) {
            return HistoricalPbResult(
                isPb = false,
                priorBestDurationMs = priorBestDurationMs,
                deltaMs = null
            )
        }

        // Effective duration: +2 penalty adds 2000ms
        val effectiveDuration = when (penalty) {
            Penalty.NONE -> durationMs
            Penalty.PLUS_TWO -> durationMs + 2000L
            Penalty.DNF -> durationMs
        }

        // Special Case: First non-DNF solve ever for this event is always an initial PB
        if (priorBestDurationMs == null) {
            return HistoricalPbResult(
                isPb = true,
                priorBestDurationMs = null,
                deltaMs = null
            )
        }

        // Strict improvement over prior best
        if (effectiveDuration < priorBestDurationMs) {
            val deltaMs = priorBestDurationMs - effectiveDuration
            return HistoricalPbResult(
                isPb = true,
                priorBestDurationMs = priorBestDurationMs,
                deltaMs = deltaMs
            )
        }

        // Slower than or tied with prior best
        return HistoricalPbResult(
            isPb = false,
            priorBestDurationMs = priorBestDurationMs,
            deltaMs = null
        )
    }

    /**
     * Convenience overload evaluating a domain [SolveTime] instance.
     */
    fun calculate(
        solve: SolveTime,
        priorBestDurationMs: Long?
    ): HistoricalPbResult = calculate(
        durationMs = solve.timeInMillis,
        penalty = solve.penalty,
        priorBestDurationMs = priorBestDurationMs
    )
}
