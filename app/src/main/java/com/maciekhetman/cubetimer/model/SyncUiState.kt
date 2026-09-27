package com.maciekhetman.cubetimer.model

/**
 * High-level status of the cloud synchronization engine.
 */
enum class SyncStatusType {
    SYNCED,
    SYNCING,
    OFFLINE,
    ERROR
}

/**
 * UI-facing state for the cloud synchronization status badge and dialog.
 */
data class SyncUiState(
    val status: SyncStatusType = SyncStatusType.OFFLINE,
    val lastSyncTime: String? = null,
    val lastSyncedAtMillis: Long? = null,
    val pendingCount: Int = 0,
    val errorMessage: String? = null,
    val isGuest: Boolean = true
)
