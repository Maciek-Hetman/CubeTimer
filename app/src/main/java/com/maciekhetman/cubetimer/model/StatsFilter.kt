package com.maciekhetman.cubetimer.model

/**
 * Filter scope for statistical calculation and solve history display in StatsScreen.
 */
sealed class StatsFilter {
    /** Show solves matching the currently active session (auto or manual). */
    data object ActiveSession : StatsFilter()

    /** Show all solves for the selected Mode across all sessions. */
    data object AllSessions : StatsFilter()
}
