package com.maciekhetman.cubetimer.data.sync

import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core interface for the bidirectional offline-first synchronization engine.
 */
interface SyncEngine {

    /** Current sync state flow (SYNCED, SYNCING, OFFLINE, ERROR, UNAUTHENTICATED). */
    val syncStatus: StateFlow<SyncStatus>

    /** Epoch millis timestamp of last successful sync. */
    val lastSyncedAt: StateFlow<Long?>

    /** Whether sync is currently running. */
    val isSyncing: StateFlow<Boolean>

    /** State manager reference for observing or updating state. */
    val stateManager: SyncStateManager

    /** Observe count of pending mutations in outbox for given owner. */
    fun observePendingMutationsCount(ownerId: String): Flow<Int>

    /** Observe unresolved OCC conflicts for given owner. */
    fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>>

    /**
     * Trigger a full sync cycle for the current user or specific owner.
     * Batches outbox mutations, applies outcomes, processes incoming changes, and advances cursor.
     */
    suspend fun sync(ownerId: String? = null): SyncResult

    /**
     * Perform snapshot bootstrap against POST /v1/snapshot when cursor expires (HTTP 409).
     * @return New watermark cursor received from snapshot.
     */
    suspend fun runSnapshotBootstrap(ownerId: String): Long

    /** Resolve conflict keeping server data. */
    suspend fun resolveConflictKeepServer(conflictId: String): Boolean

    /** Resolve conflict keeping local data (re-enqueues mutation). */
    suspend fun resolveConflictKeepLocal(conflictId: String): Boolean
}
