package com.maciekhetman.cubetimer.data.session

import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing Session persistence, reactive streams,
 * and transactional outbox mutation dispatch.
 */
interface SessionRepository {

    /**
     * Observe active (non-archived, non-deleted) sessions for a given owner and mode.
     */
    fun observeActiveSessions(ownerId: String, mode: Mode): Flow<List<Session>>

    /**
     * Observe all (including archived, non-deleted) sessions for a given owner and mode.
     */
    fun observeAllSessions(ownerId: String, mode: Mode): Flow<List<Session>>

    /**
     * Observe archived (non-deleted) sessions for a given owner and mode.
     */
    fun observeArchivedSessions(ownerId: String, mode: Mode): Flow<List<Session>>

    /**
     * Observe a specific session by ID.
     */
    fun observeSessionById(id: String): Flow<Session?>

    /**
     * One-shot fetch for a session by ID.
     */
    suspend fun getSessionById(id: String): Session?

    /**
     * Fetch open automatic session for (ownerId, mode).
     */
    suspend fun getOpenAutomaticSession(ownerId: String, mode: Mode): Session?

    /**
     * Get all active (non-archived, non-deleted) sessions for owner and mode.
     */
    suspend fun getActiveSessions(ownerId: String, mode: Mode): List<Session>

    /**
     * Get session names matching a prefix for duplicate disambiguation.
     */
    suspend fun getSessionNamesWithPrefix(ownerId: String, mode: Mode, namePrefix: String): List<String>

    /**
     * Create a new session and enqueue outbox mutation if authenticated.
     */
    suspend fun createSession(session: Session): Session

    /**
     * Create a new manual session.
     */
    suspend fun createManualSession(name: String, mode: Mode, ownerId: String = "guest"): Session

    /**
     * Rename an existing session.
     */
    suspend fun renameSession(id: String, newName: String, ownerId: String = "guest"): Session?

    /**
     * Archive a session (and close it if open).
     */
    suspend fun archiveSession(id: String, ownerId: String = "guest"): Session?

    /**
     * Unarchive a session.
     */
    suspend fun unarchiveSession(id: String, ownerId: String = "guest"): Session?

    /**
     * Close an active automatic or manual session by setting ended_at.
     */
    suspend fun closeSession(id: String, ownerId: String = "guest"): Session?

    /**
     * Soft delete a session and enqueue delete mutation in outbox if authenticated.
     */
    suspend fun deleteSession(id: String, ownerId: String = "guest"): Boolean
}
