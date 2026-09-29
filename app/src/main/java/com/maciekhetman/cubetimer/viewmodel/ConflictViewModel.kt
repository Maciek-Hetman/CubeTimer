package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.CubeTimerApplication
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.data.sync.SyncEngine
import com.maciekhetman.cubetimer.model.currentUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Exposes unresolved sync conflicts of the signed-in owner and lets the user pick which side wins.
 * Guests never have conflicts. After a successful resolution [onResolved] is invoked so the
 * resolution is pushed to the server promptly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictViewModel(
    application: Application,
    private val syncEngine: SyncEngine,
    authManager: AuthManager,
    private val onResolved: () -> Unit,
    json: Json = NetworkModule.json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mappingDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        syncEngine = (application as CubeTimerApplication).syncEngine,
        authManager = (application as CubeTimerApplication).authManager,
        onResolved = { (application as CubeTimerApplication).syncStateManager.triggerSync() }
    )

    val conflicts: StateFlow<List<ConflictUiModel>> = authManager.authState
        .flatMapLatest { auth ->
            // currentUser covers both Authenticated and Admin; guests (and Loading) have no conflicts.
            val user = auth.currentUser
            if (user != null) {
                syncEngine.observeUnresolvedConflicts(user.id)
                    .map { list -> list.map { ConflictUiMapper.map(it, json) } }
            } else {
                flowOf(emptyList())
            }
        }
        .flowOn(mappingDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _resolvingIds = MutableStateFlow<Set<String>>(emptySet())
    /** Conflicts with a resolution in flight; their buttons should be disabled. */
    val resolvingIds: StateFlow<Set<String>> = _resolvingIds.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun keepLocal(conflictId: String) = resolve(conflictId) { syncEngine.resolveConflictKeepLocal(it) }

    fun keepServer(conflictId: String) = resolve(conflictId) { syncEngine.resolveConflictKeepServer(it) }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun resolve(conflictId: String, action: suspend (String) -> Boolean) {
        if (conflictId in _resolvingIds.value) return
        _resolvingIds.update { it + conflictId }
        _errorMessage.value = null
        viewModelScope.launch {
            val resolved = try {
                withContext(ioDispatcher) { action(conflictId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _resolvingIds.update { it - conflictId }
            if (resolved) {
                onResolved()
            } else {
                _errorMessage.value = "Couldn't resolve the conflict. Try again."
            }
        }
    }
}
