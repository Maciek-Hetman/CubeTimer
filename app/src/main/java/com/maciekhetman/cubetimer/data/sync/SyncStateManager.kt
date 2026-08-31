package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.SyncUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages reactive synchronization state, network connectivity, and UI observables.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStateManager(
    initialStatus: SyncStatus = SyncStatus.UNAUTHENTICATED,
    initialLastSyncedAt: Long? = null,
    private val context: Context? = null,
    private val database: CubeDatabase? = null,
    private val authManager: AuthManager? = null,
    private val onTriggerSync: (suspend () -> Unit)? = null,
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _syncStatus = MutableStateFlow(initialStatus)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow<Long?>(initialLastSyncedAt)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    private val _isSyncing = MutableStateFlow(initialStatus == SyncStatus.SYNCING)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    private val _isOnline = MutableStateFlow(checkInitialConnectivity())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        registerNetworkCallback()
    }

    private data class LocalSyncState(
        val isOnline: Boolean,
        val isSyncing: Boolean,
        val syncStatus: SyncStatus,
        val lastErrorMessage: String?
    )

    private val localSyncStateFlow: Flow<LocalSyncState> = combine(
        _isOnline,
        _isSyncing,
        _syncStatus,
        _lastErrorMessage
    ) { online, isSyncing, syncStatus, lastErrorMsg ->
        LocalSyncState(online, isSyncing, syncStatus, lastErrorMsg)
    }

    val syncUiState: StateFlow<SyncUiState> = if (database != null && authManager != null) {
        authManager.authState.flatMapLatest { auth ->
            if (auth !is AuthState.Authenticated && auth !is AuthState.Admin) {
                flowOf(
                    SyncUiState(
                        status = SyncStatusType.SYNCED,
                        lastSyncTime = null,
                        lastSyncedAtMillis = null,
                        pendingCount = 0,
                        errorMessage = null,
                        isGuest = true
                    )
                )
            } else {
                val ownerId = (auth as? AuthState.Authenticated)?.user?.id
                    ?: (auth as? AuthState.Admin)?.user?.id
                    ?: "guest"
                val metadataFlow = database.syncMetadataDao().observeMetadata(ownerId)
                val pendingFlow = database.syncOutboxDao().observePendingCount(ownerId)

                combine(
                    metadataFlow,
                    pendingFlow,
                    localSyncStateFlow
                ) { metadata, pendingCount, local ->
                    when {
                        metadata?.lastError != null -> SyncUiState(
                            status = SyncStatusType.ERROR,
                            lastSyncTime = metadata.lastSyncTime,
                            pendingCount = pendingCount,
                            errorMessage = metadata.lastError,
                            isGuest = false
                        )
                        metadata?.isSyncing == true || local.isSyncing -> SyncUiState(
                            status = SyncStatusType.SYNCING,
                            lastSyncTime = metadata?.lastSyncTime,
                            pendingCount = pendingCount,
                            errorMessage = null,
                            isGuest = false
                        )
                        !local.isOnline -> SyncUiState(
                            status = SyncStatusType.OFFLINE,
                            lastSyncTime = metadata?.lastSyncTime,
                            pendingCount = pendingCount,
                            errorMessage = null,
                            isGuest = false
                        )
                        local.syncStatus == SyncStatus.ERROR -> SyncUiState(
                            status = SyncStatusType.ERROR,
                            lastSyncTime = metadata?.lastSyncTime,
                            pendingCount = pendingCount,
                            errorMessage = local.lastErrorMessage ?: "Sync failed",
                            isGuest = false
                        )
                        else -> SyncUiState(
                            status = SyncStatusType.SYNCED,
                            lastSyncTime = metadata?.lastSyncTime,
                            pendingCount = pendingCount,
                            errorMessage = null,
                            isGuest = false
                        )
                    }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, SyncUiState(isGuest = true))
    } else {
        // Fallback for isolated unit tests or simple state manager instances
        combine(_syncStatus, _lastSyncedAt, _lastErrorMessage) { status, lastSynced, errMsg ->
            val uiStatus = when (status) {
                SyncStatus.SYNCED -> SyncStatusType.SYNCED
                SyncStatus.SYNCING -> SyncStatusType.SYNCING
                SyncStatus.OFFLINE -> SyncStatusType.OFFLINE
                SyncStatus.ERROR -> SyncStatusType.ERROR
                SyncStatus.UNAUTHENTICATED -> SyncStatusType.SYNCED
            }
            SyncUiState(
                status = uiStatus,
                lastSyncTime = lastSynced?.toString(),
                lastSyncedAtMillis = lastSynced,
                pendingCount = 0,
                errorMessage = errMsg,
                isGuest = (status == SyncStatus.UNAUTHENTICATED)
            )
        }.stateIn(scope, SharingStarted.Eagerly, SyncUiState(isGuest = (initialStatus == SyncStatus.UNAUTHENTICATED)))
    }

    fun setSyncing() {
        _syncStatus.value = SyncStatus.SYNCING
        _isSyncing.value = true
        _lastErrorMessage.value = null
    }

    fun setSynced(timestampMs: Long = System.currentTimeMillis()) {
        _syncStatus.value = SyncStatus.SYNCED
        _isSyncing.value = false
        _lastSyncedAt.value = timestampMs
        _lastErrorMessage.value = null
    }

    fun setOffline() {
        _syncStatus.value = SyncStatus.OFFLINE
        _isSyncing.value = false
    }

    fun setError(message: String?) {
        _syncStatus.value = SyncStatus.ERROR
        _isSyncing.value = false
        _lastErrorMessage.value = message
    }

    fun setUnauthenticated() {
        _syncStatus.value = SyncStatus.UNAUTHENTICATED
        _isSyncing.value = false
        _lastErrorMessage.value = null
    }

    fun updateStatus(status: SyncStatus) {
        _syncStatus.value = status
        _isSyncing.value = (status == SyncStatus.SYNCING)
    }

    fun setOnlineForTest(online: Boolean) {
        _isOnline.value = online
    }

    fun triggerSync() {
        scope.launch {
            onTriggerSync?.invoke()
        }
    }

    private fun checkInitialConnectivity(): Boolean {
        if (context == null) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val active = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(active) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun registerNetworkCallback() {
        if (context == null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                }

                override fun onLost(network: Network) {
                    _isOnline.value = false
                }
            })
        } catch (_: Exception) {
            // In unit test environments without network service mock, ignore
        }
    }
}
