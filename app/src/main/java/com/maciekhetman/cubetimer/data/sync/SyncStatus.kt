package com.maciekhetman.cubetimer.data.sync

/**
 * Reactive status of the synchronization engine.
 */
enum class SyncStatus {
    /** Up to date with backend. */
    SYNCED,

    /** Synchronization cycle currently in progress. */
    SYNCING,

    /** Device is offline or network is unreachable. */
    OFFLINE,

    /** Encountered an error during last synchronization attempt. */
    ERROR,

    /** App is in guest mode or user is unauthenticated. */
    UNAUTHENTICATED
}
