package com.maciekhetman.cubetimer.data.sync

import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import kotlinx.coroutines.flow.Flow

/**
 * Deterministic conflict resolution policies for optimistic concurrency collisions.
 */
enum class ConflictPolicy {
    /** Overwrite local record with server record and version. Discard pending local mutation. */
    SERVER_WINS,

    /** Re-enqueue local record as a new mutation targeting the server's version. */
    LOCAL_WINS
}

/**
 * Interface managing conflict detection, storage, and deterministic resolution.
 */
interface ConflictResolver {

    /** Observe unresolved conflicts for an owner (e.g. for UI prompts). */
    fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>>

    /** Get a single conflict by ID. */
    suspend fun getConflictById(conflictId: String): ConflictEntity?

    /** Record a new conflict in the database. */
    suspend fun recordConflict(
        ownerId: String,
        mutationId: String,
        entityType: String,
        entityId: String,
        serverVersion: Long,
        serverUpdatedAt: String?,
        localPayloadJson: String?,
        serverPayloadJson: String?,
        errorMessage: String = "Conflict detected: server version mismatch"
    ): ConflictEntity

    /** Resolve a specific conflict using the given policy. */
    suspend fun resolveConflict(conflictId: String, policy: ConflictPolicy): Boolean

    /** Convenience helper for SERVER_WINS. */
    suspend fun resolveKeepServer(conflictId: String): Boolean

    /** Convenience helper for LOCAL_WINS. */
    suspend fun resolveKeepLocal(conflictId: String): Boolean
}
