package com.maciekhetman.cubetimer.data.session

import android.content.Context
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

    private fun sessionModeKey(mode: Mode) = stringPreferencesKey("session_mode_${mode.name}")
    private fun activeManualSessionKey(mode: Mode) = stringPreferencesKey("active_manual_session_${mode.name}")

    override fun getSessionModeFlow(mode: Mode): Flow<SessionKind> {
        return context.settingsDataStore.data.map { prefs ->
            val raw = prefs[sessionModeKey(mode)]
            SessionKind.fromString(raw)
        }.distinctUntilChanged()
    }

    override fun isAutomaticModeFlow(mode: Mode): Flow<Boolean> {
        return getSessionModeFlow(mode).map { it == SessionKind.AUTOMATIC }.distinctUntilChanged()
    }

    override suspend fun setSessionMode(mode: Mode, kind: SessionKind) {
        context.settingsDataStore.edit { prefs ->
            prefs[sessionModeKey(mode)] = kind.value
        }
    }

    override suspend fun setAutomaticMode(mode: Mode, enabled: Boolean) {
        setSessionMode(mode, if (enabled) SessionKind.AUTOMATIC else SessionKind.MANUAL)
    }

    override fun getActiveSessionFlow(mode: Mode): Flow<Session?> {
        return authManager.authState.flatMapLatest {
            val ownerId = authManager.currentOwnerId
            getActiveSessionFlow(ownerId, mode)
        }.distinctUntilChanged()
    }

    override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> {
        return context.settingsDataStore.data.flatMapLatest { prefs ->
            val kind = SessionKind.fromString(prefs[sessionModeKey(mode)])
            if (kind == SessionKind.AUTOMATIC) {
                sessionRepository.observeActiveSessions(ownerId, mode).map { sessions ->
                    sessions.firstOrNull { it.kind == SessionKind.AUTOMATIC && it.isOpen }
                }
            } else {
                val manualId = prefs[activeManualSessionKey(mode)]
                if (manualId.isNullOrBlank()) {
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
        }.distinctUntilChanged()
    }

    override suspend fun setActiveSession(mode: Mode, sessionId: String) {
        val targetOwner = authManager.currentOwnerId
        setActiveSession(targetOwner, mode, sessionId)
    }

    override suspend fun setActiveSession(ownerId: String, mode: Mode, sessionId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[sessionModeKey(mode)] = SessionKind.MANUAL.value
            prefs[activeManualSessionKey(mode)] = sessionId
        }
    }

    override suspend fun getOrCreateActiveSession(
        ownerId: String,
        mode: Mode,
        solveTimestamp: Long?
    ): Session = sessionMutex.withLock {
        withContext(ioDispatcher) {
            val prefs = context.settingsDataStore.data.first()
            val kind = SessionKind.fromString(prefs[sessionModeKey(mode)])
            val nowEpochMs = solveTimestamp ?: System.currentTimeMillis()

            if (kind == SessionKind.AUTOMATIC) {
                resolveAutomaticSession(ownerId, mode, nowEpochMs)
            } else {
                resolveManualSession(ownerId, mode, prefs[activeManualSessionKey(mode)], nowEpochMs)
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
            val lastActivityMs = lastSolve?.let { CubeTypeConverters.isoToEpochMillis(it.solvedAt) }
                ?: CubeTypeConverters.isoToEpochMillis(openSession.startedAt)

            val elapsedMs = currentTimestampMs - lastActivityMs

            // Check 60-minute inactivity gap reuse rule
            if (elapsedMs in 0..AutomaticSessionHelper.DEFAULT_INACTIVITY_GAP_MILLIS) {
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
            val currentManualId = prefs[activeManualSessionKey(mode)]
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
        val deleted = sessionRepository.deleteSession(id, targetOwner)

        if (deleted && mode != null) {
            val prefs = context.settingsDataStore.data.first()
            val currentManualId = prefs[activeManualSessionKey(mode)]
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
            prefs[sessionModeKey(mode)] = SessionKind.AUTOMATIC.value
            prefs.remove(activeManualSessionKey(mode))
        }
    }
}
