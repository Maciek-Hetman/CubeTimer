package com.maciekhetman.cubetimer.data.sync

/**
 * Result returned by a synchronization cycle.
 */
sealed class SyncResult {

    /**
     * Sync cycle finished successfully.
     * @param mutationsSynced Number of local mutations accepted, rejected, or resolved.
     * @param changesApplied Number of server changes applied locally.
     * @param conflictsRecorded Number of OCC conflicts encountered.
     */
    data class Success(
        val mutationsSynced: Int = 0,
        val changesApplied: Int = 0,
        val conflictsRecorded: Int = 0
    ) : SyncResult()

    /**
     * Nothing to sync (e.g. guest mode or clean state).
     */
    object NoOp : SyncResult()

    /**
     * Sync could not proceed because device is offline.
     */
    data class Offline(val message: String = "Device is offline or network unreachable") : SyncResult()

    /**
     * Sync could not proceed due to invalid or missing authentication.
     */
    data class AuthError(val message: String = "User is unauthenticated or credentials expired") : SyncResult()

    /**
     * General failure during sync.
     */
    data class Error(val message: String, val cause: Throwable? = null) : SyncResult()
}
