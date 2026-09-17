package com.maciekhetman.cubetimer.data.session

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.domain.session.AutomaticSessionHelper
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerImpl(
    private val context: Context,
    private val sessionRepository: SessionRepository,
    private val solveDao: SolveDao,
    private val authManager: AuthManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SessionManager {

    private val sessionMutex = Mutex()

    private val HIDE_SESSION_MENU_KEY = booleanPreferencesKey("hide_session_menu_in_top_bar")

    // Legacy, unscoped keys from before manual session selection was scoped per-owner. Only ever
    // consulted as a fallback for the "guest" owner so pre-existing selections aren't lost.
    private fun legacySessionModeKey(mode: Mode) = stringPreferencesKey("session_mode_${mode.name}")
    private fun legacyActiveManualSessionKey(mode: Mode) = stringPreferencesKey("active_manual_session_${mode.name}")

    private fun sessionModeKey(ownerId: String, mode: Mode) = stringPreferencesKey("session_mode_${ownerId}_${mode.name}")
    private fun activeManualSessionKey(ownerId: String, mode: Mode) = stringPreferencesKey("active_manual_session_${ownerId}_${mode.name}")

    private fun readSessionModeRaw(prefs: Preferences, ownerId: String, mode: Mode): String? {
        val scoped = prefs[sessionModeKey(ownerId, mode)]
        if (scoped != null) return scoped
        return if (ownerId == "guest") prefs[legacySessionModeKey(mode)] else null
    }

    private fun readActiveManualSessionId(prefs: Preferences, ownerId: String, mode: Mode): String? {
        val scoped = prefs[activeManualSessionKey(ownerId, mode)]
        if (scoped != null) return scoped
        return if (ownerId == "guest") prefs[legacyActiveManualSessionKey(mode)] else null
    }

    override fun getSessionModeFlow(mode: Mode): Flow<SessionKind> {
        return authManager.authState.map { authManager.currentOwnerId }.distinctUntilChanged().flatMapLatest { ownerId ->
            context.settingsDataStore.data.map { prefs ->
                if (prefs[HIDE_SESSION_MENU_KEY] == true) {
                    SessionKind.AUTOMATIC
                } else {
                    SessionKind.fromString(readSessionModeRaw(prefs, ownerId, mode))
                }
            }.distinctUntilChanged()
        }.distinctUntilChanged()
    }

    override fun isAutomaticModeFlow(mode: Mode): Flow<Boolean> {
        return getSessionModeFlow(mode).map { it == SessionKind.AUTOMATIC }.distinctUntilChanged()
    }

    override suspend fun setSessionMode(mode: Mode, kind: SessionKind) {
        val ownerId = authManager.currentOwnerId
        val isLocked = context.settingsDataStore.data.map { it[HIDE_SESSION_MENU_KEY] == true }.first()
        if (isLocked && kind != SessionKind.AUTOMATIC) return
        context.settingsDataStore.edit { prefs ->
            prefs[sessionModeKey(ownerId, mode)] = kind.value
        }
    }

    override suspend fun setAutomaticMode(mode: Mode, enabled: Boolean) {
        val isLocked = context.settingsDataStore.data.map { it[HIDE_SESSION_MENU_KEY] == true }.first()
        if (isLocked && !enabled) return
        setSessionMode(mode, if (enabled) SessionKind.AUTOMATIC else SessionKind.MANUAL)
    }

    override fun getActiveSessionFlow(mode: Mode): Flow<Session?> {
        return authManager.authState.flatMapLatest {
            val ownerId = authManager.currentOwnerId
            getActiveSessionFlow(ownerId, mode)
        }.distinctUntilChanged()
    }

    override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> {
        // Reduce to just the (kind, manualSessionId) pair relevant to this mode/owner and
        // distinctUntilChanged BEFORE flatMapLatest, so unrelated settings changes (theme,
        // haptics, ...) don't tear down and re-run the underlying Room session queries.
        return context.settingsDataStore.data
            .map { prefs ->
                val kind = if (prefs[HIDE_SESSION_MENU_KEY] == true) {
                    SessionKind.AUTOMATIC
                } else {
                    SessionKind.fromString(readSessionModeRaw(prefs, ownerId, mode))
                }
                val manualId = if (kind == SessionKind.MANUAL) readActiveManualSessionId(prefs, ownerId, mode) else null
                kind to manualId
            }
            .distinctUntilChanged()
            .flatMapLatest { (kind, manualId) ->
                if (kind == SessionKind.AUTOMATIC) {
                    sessionRepository.observeActiveSessions(ownerId, mode).map { sessions ->
                        sessions.firstOrNull { it.kind == SessionKind.AUTOMATIC && it.isOpen }
                    }
                } else if (manualId.isNullOrBlank()) {
                    sessionRepository.observeActiveSessions(ownerId, mode).map { sessions ->
                        sessions.firstOrNull { it.kind == SessionKind.MANUAL && it.isOpen }
                    }
                } else {
                    sessionRepository.observeSessionById(manualId).map { session ->
                        if (session != null && session.isOpen && session.ownerId == ownerId && session.event == mode) {
                            session
                        } else {
                            null
                        }
                    }
                }
            }
            .distinctUntilChanged()
    }

    override suspend fun setActiveSession(mode: Mode, sessionId: String) {
        val targetOwner = authManager.currentOwnerId
        setActiveSession(targetOwner, mode, sessionId)
    }

    override suspend fun setActiveSession(ownerId: String, mode: Mode, sessionId: String) {
        val isLocked = context.settingsDataStore.data.map { it[HIDE_SESSION_MENU_KEY] == true }.first()
        if (isLocked) return
        context.settingsDataStore.edit { prefs ->
            prefs[sessionModeKey(ownerId, mode)] = SessionKind.MANUAL.value
            prefs[activeManualSessionKey(ownerId, mode)] = sessionId
        }
    }

    override suspend fun getOrCreateActiveSession(
        ownerId: String,
        mode: Mode,
        solveTimestamp: Long?
    ): Session = sessionMutex.withLock {
        withContext(ioDispatcher) {
            val prefs = context.settingsDataStore.data.first()
            val kind = if (prefs[HIDE_SESSION_MENU_KEY] == true) SessionKind.AUTOMATIC else SessionKind.fromString(readSessionModeRaw(prefs, ownerId, mode))
            val nowEpochMs = solveTimestamp ?: System.currentTimeMillis()

            if (kind == SessionKind.AUTOMATIC) {
                resolveAutomaticSession(ownerId, mode, nowEpochMs)
            } else {
                resolveManualSession(ownerId, mode, readActiveManualSessionId(prefs, ownerId, mode), nowEpochMs)
            }
        }
    }

    private suspend fun resolveAutomaticSession(
        ownerId: String,
        mode: Mode,
        currentTimestampMs: Long
    ): Session {
        val openSession = sessionRepository.getOpenAutomaticSession(ownerId, mode)

        if (openSession != null) {
            val lastSolve = solveDao.getLastSolveForSession(ownerId, openSession.id)
            val lastSolveTimestampMs = lastSolve?.let { CubeTypeConverters.isoToEpochMillis(it.solvedAt) }

            // Reuse the same 60-minute inactivity gap rule as AutomaticSessionHelper (falls back
            // to session.startedAt when there is no last solve) instead of re-implementing it here.
            if (AutomaticSessionHelper.shouldReuseAutomaticSession(
                    session = openSession,
                    lastSolveTimestampMs = lastSolveTimestampMs,
                    nowMs = currentTimestampMs,
                    mode = mode
                )
            ) {
                return openSession
            }

            // Exceeded inactivity gap: close previous session
            sessionRepository.closeSession(openSession.id, ownerId)
        }

        // Create new automatic session with disambiguated name
        val instant = Instant.ofEpochMilli(currentTimestampMs)
        val baseName = AutomaticSessionHelper.automaticSessionName(instant)
        val existingNames = sessionRepository.getSessionNamesWithPrefix(ownerId, mode, baseName)
        val disambiguatedName = AutomaticSessionHelper.disambiguateSessionName(baseName, existingNames)

        val newSession = Session(
            id = UUID.randomUUID().toString(),
            ownerId = ownerId,
            name = disambiguatedName,
            event = mode,
            kind = SessionKind.AUTOMATIC,
            archived = false,
            startedAt = instant.toString(),
            endedAt = null,
            version = 0L,
            updatedAt = instant.toString(),
            deletedAt = null
        )

        return sessionRepository.createSession(newSession)
    }

    private suspend fun resolveManualSession(
        ownerId: String,
        mode: Mode,
        selectedManualId: String?,
        currentTimestampMs: Long
    ): Session {
        if (!selectedManualId.isNullOrBlank()) {
            val session = sessionRepository.getSessionById(selectedManualId)
            if (session != null && session.isOpen && session.ownerId == ownerId && session.event == mode) {
                return session
            }
        }

        // Fallback: look for any active manual session
        val activeSessions = sessionRepository.getActiveSessions(ownerId, mode)
        val existingManual = activeSessions.firstOrNull { it.kind == SessionKind.MANUAL }
        if (existingManual != null) {
            setActiveSession(ownerId, mode, existingManual.id)
            return existingManual
        }

        // Create default manual session if none exists
        val instant = Instant.ofEpochMilli(currentTimestampMs)
        val defaultSession = Session(
            id = UUID.randomUUID().toString(),
            ownerId = ownerId,
            name = "Default Session",
            event = mode,
            kind = SessionKind.MANUAL,
            archived = false,
            startedAt = instant.toString(),
            endedAt = null,
            version = 0L,
            updatedAt = instant.toString(),
            deletedAt = null
        )

        val created = sessionRepository.createSession(defaultSession)
        setActiveSession(ownerId, mode, created.id)
        return created
    }

    override suspend fun createManualSession(
        name: String,
        mode: Mode,
        ownerId: String?
    ): Session = withContext(ioDispatcher) {
        val targetOwner = ownerId ?: authManager.currentOwnerId
        val created = sessionRepository.createManualSession(name, mode, targetOwner)
        setActiveSession(targetOwner, mode, created.id)
        created
    }

    override suspend fun renameSession(
        id: String,
        newName: String,
        ownerId: String?
    ): Session? = withContext(ioDispatcher) {
        val targetOwner = ownerId ?: authManager.currentOwnerId
        sessionRepository.renameSession(id, newName.trim(), targetOwner)
    }

    override suspend fun archiveSession(
        id: String,
        mode: Mode?,
        ownerId: String?
    ): Session? = withContext(ioDispatcher) {
        val targetOwner = ownerId ?: authManager.currentOwnerId
        val archived = sessionRepository.archiveSession(id, targetOwner)

        if (mode != null) {
            val prefs = context.settingsDataStore.data.first()
            val currentManualId = readActiveManualSessionId(prefs, targetOwner, mode)
            if (currentManualId == id) {
                clearManualSessionOverride(targetOwner, mode)
            }
        }
        archived
    }

    override suspend fun unarchiveSession(
        id: String,
        ownerId: String?
    ): Session? = withContext(ioDispatcher) {
        val targetOwner = ownerId ?: authManager.currentOwnerId
        sessionRepository.unarchiveSession(id, targetOwner)
    }

    override suspend fun deleteSession(
        id: String,
        mode: Mode?,
        ownerId: String?
    ): Boolean = withContext(ioDispatcher) {
        val targetOwner = ownerId ?: authManager.currentOwnerId
        // Cascade: deleteSession alone leaves the session's solves orphaned (owner_id pointing at
        // a soft-deleted session). Use the cascading path, matching History's delete flow.
        val snapshot = sessionRepository.deleteSessionWithSolves(id, targetOwner)
        val deleted = snapshot != null

        if (deleted && mode != null) {
            val prefs = context.settingsDataStore.data.first()
            val currentManualId = readActiveManualSessionId(prefs, targetOwner, mode)
            if (currentManualId == id) {
                clearManualSessionOverride(targetOwner, mode)
            }
        }
        deleted
    }

    override suspend fun clearManualSessionOverride(mode: Mode) {
        val targetOwner = authManager.currentOwnerId
        clearManualSessionOverride(targetOwner, mode)
    }

    override suspend fun clearManualSessionOverride(ownerId: String, mode: Mode) {
        context.settingsDataStore.edit { prefs ->
            prefs[sessionModeKey(ownerId, mode)] = SessionKind.AUTOMATIC.value
            prefs.remove(activeManualSessionKey(ownerId, mode))
            if (ownerId == "guest") {
                // Also clear the legacy unscoped key, otherwise readActiveManualSessionId's
                // guest fallback would resurrect the stale override we just cleared.
                prefs.remove(legacyActiveManualSessionKey(mode))
            }
        }
    }
}
