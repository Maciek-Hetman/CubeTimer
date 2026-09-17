package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.session.SessionRepository
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    application: Application,
    private val sessionManager: SessionManager,
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager
) : AndroidViewModel(application) {

    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Active session for the currently selected Mode.
     */
    val activeSession: StateFlow<Session?> = combine(_currentMode, authManager.authState) { mode, _ ->
        val ownerId = authManager.currentOwnerId
        sessionManager.getActiveSessionFlow(ownerId, mode)
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Active (non-archived, non-deleted) sessions for current mode.
     */
    val sessionsList: StateFlow<List<Session>> = combine(_currentMode, authManager.authState) { mode, _ ->
        val ownerId = authManager.currentOwnerId
        sessionRepository.observeActiveSessions(ownerId, mode)
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Archived (non-deleted) sessions for current mode.
     */
    val archivedSessionsList: StateFlow<List<Session>> = combine(_currentMode, authManager.authState) { mode, _ ->
        val ownerId = authManager.currentOwnerId
        sessionRepository.observeArchivedSessions(ownerId, mode)
    }.flatMapLatest { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Whether automatic session mode is active for the current puzzle mode.
     */
    val isAutomaticMode: StateFlow<Boolean> = _currentMode
        .flatMapLatest { mode -> sessionManager.isAutomaticModeFlow(mode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setMode(mode: Mode) {
        _currentMode.value = mode
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Switch active manual session to a specific session ID.
     */
    fun switchSession(sessionId: String): Job = viewModelScope.launch {
        try {
            val ownerId = authManager.currentOwnerId
            sessionManager.setActiveSession(ownerId, _currentMode.value, sessionId)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to switch session: ${e.message}"
        }
    }

    /**
     * Set automatic mode enabled or disabled.
     */
    fun setAutomaticMode(enabled: Boolean): Job = viewModelScope.launch {
        try {
            sessionManager.setAutomaticMode(_currentMode.value, enabled)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update session mode: ${e.message}"
        }
    }

    /**
     * Switch back to automatic session grouping mode.
     */
    fun switchToAutomaticSession(): Job = viewModelScope.launch {
        try {
            val ownerId = authManager.currentOwnerId
            sessionManager.clearManualSessionOverride(ownerId, _currentMode.value)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to switch to automatic session: ${e.message}"
        }
    }

    /**
     * Create a new manual session and activate it.
     */
    fun createManualSession(name: String): Job = viewModelScope.launch {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            _errorMessage.value = "Session name cannot be empty"
            return@launch
        }

        try {
            val ownerId = authManager.currentOwnerId
            // sessionManager.createManualSession already activates the newly created session, so no
            // separate setActiveSession call is needed here.
            sessionManager.createManualSession(
                name = trimmedName,
                mode = _currentMode.value,
                ownerId = ownerId
            )
        } catch (e: Exception) {
            _errorMessage.value = "Failed to create session: ${e.message}"
        }
    }

    /**
     * Rename an existing session.
     */
    fun renameSession(sessionId: String, newName: String): Job = viewModelScope.launch {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) {
            _errorMessage.value = "Session name cannot be empty"
            return@launch
        }

        try {
            val ownerId = authManager.currentOwnerId
            sessionManager.renameSession(sessionId, trimmedName, ownerId)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to rename session: ${e.message}"
        }
    }

    /**
     * Archive a session. If active, switches away from it.
     */
    fun archiveSession(sessionId: String): Job = viewModelScope.launch {
        try {
            val ownerId = authManager.currentOwnerId
            sessionManager.archiveSession(sessionId, _currentMode.value, ownerId)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to archive session: ${e.message}"
        }
    }

    /**
     * Unarchive a session.
     */
    fun unarchiveSession(sessionId: String): Job = viewModelScope.launch {
        try {
            val ownerId = authManager.currentOwnerId
            sessionManager.unarchiveSession(sessionId, ownerId)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to unarchive session: ${e.message}"
        }
    }

    /**
     * Soft delete a session. If active, switches back to automatic session.
     */
    fun deleteSession(sessionId: String): Job = viewModelScope.launch {
        try {
            val ownerId = authManager.currentOwnerId
            sessionManager.deleteSession(sessionId, _currentMode.value, ownerId)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to delete session: ${e.message}"
        }
    }
}
