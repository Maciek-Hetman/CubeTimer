package com.maciekhetman.cubetimer.data.session

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.flow.Flow

/**
 * Interface coordinating active session state per puzzle Mode,
 * automatic session grouping and inactivity evaluation,
 * and manual session selection and lifecycle.
 */
interface SessionManager {

    /**
     * Observe the active session for a given mode using the current authenticated owner.
     */
    fun getActiveSessionFlow(mode: Mode): Flow<Session?>

    /**
     * Alias for getActiveSessionFlow(mode).
     */
    fun activeSessionFlow(mode: Mode): Flow<Session?> = getActiveSessionFlow(mode)

    /**
     * Observe the active session for a specific owner and mode.
     */
    fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?>

    /**
     * Observe the session grouping mode (AUTOMATIC vs MANUAL) for a given mode.
     */
    fun getSessionModeFlow(mode: Mode): Flow<SessionKind>

    /**
     * Observe whether automatic session mode is active for a given mode.
     */
    fun isAutomaticModeFlow(mode: Mode): Flow<Boolean>

    /**
     * Set the session grouping mode (AUTOMATIC vs MANUAL) for a given mode.
     */
    suspend fun setSessionMode(mode: Mode, kind: SessionKind)

    /**
     * Toggle automatic session mode on or off for a given mode.
     */
    suspend fun setAutomaticMode(mode: Mode, enabled: Boolean)

    /**
     * Switch active manual session for a mode.
     */
    suspend fun setActiveSession(mode: Mode, sessionId: String)

    /**
     * Switch active manual session for an owner and mode.
     */
    suspend fun setActiveSession(ownerId: String, mode: Mode, sessionId: String)

    /**
     * Get or create the active session for timing a solve.
     * Evaluates auto vs manual mode:
     * - In AUTOMATIC mode: reuses open auto session if <= 60 min gap, else closes & creates new.
     * - In MANUAL mode: returns selected manual session or creates default manual session.
     */
    suspend fun getOrCreateActiveSession(
        ownerId: String,
        mode: Mode,
        solveTimestamp: Long? = null
    ): Session

    /**
     * Create a new manual session, set it as active, and return it.
     */
    suspend fun createManualSession(
        name: String,
        mode: Mode,
        ownerId: String? = null
    ): Session

    /**
     * Rename an existing session.
     */
    suspend fun renameSession(
        id: String,
        newName: String,
        ownerId: String? = null
    ): Session?

    /**
     * Archive a session. If it was active, transitions cleanly away from it.
     */
    suspend fun archiveSession(
        id: String,
        mode: Mode? = null,
        ownerId: String? = null
    ): Session?

    /**
     * Unarchive a session.
     */
    suspend fun unarchiveSession(
        id: String,
        ownerId: String? = null
    ): Session?

    /**
     * Soft delete a session. If active, transitions cleanly away from it.
     */
    suspend fun deleteSession(
        id: String,
        mode: Mode? = null,
        ownerId: String? = null
    ): Boolean

    /**
     * Clear manual session selection for a mode, returning to automatic mode.
     */
    suspend fun clearManualSessionOverride(mode: Mode)

    /**
     * Clear manual session selection for a specific owner and mode.
     */
    suspend fun clearManualSessionOverride(ownerId: String, mode: Mode)

    /**
     * Switch back to automatic mode.
     */
    suspend fun switchToAutomatic(mode: Mode) = clearManualSessionOverride(mode)
}
