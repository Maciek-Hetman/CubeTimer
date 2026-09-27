package com.maciekhetman.cubetimer.data.session

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Owns the active-session policy per puzzle [Mode]. Sessions are always automatic: solves are
 * grouped into `"${day} ${month} ${year} ${dayPart}"` sessions, and an open session is reused only
 * while the gap since its last solve is within the inactivity window (see AutomaticSessionHelper).
 */
interface SessionManager {

    /**
     * Observe the active (open automatic) session for a given mode using the current authenticated owner.
     */
    fun getActiveSessionFlow(mode: Mode): Flow<Session?>

    /**
     * Observe the active (open automatic) session for a specific owner and mode.
     */
    fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?>

    /**
     * Get or create the automatic session a solve timed at [solveTimestamp] belongs to: reuses the
     * open automatic session if the inactivity gap allows, otherwise closes it and opens a new one.
     */
    suspend fun getOrCreateActiveSession(
        ownerId: String,
        mode: Mode,
        solveTimestamp: Long? = null
    ): Session
}
