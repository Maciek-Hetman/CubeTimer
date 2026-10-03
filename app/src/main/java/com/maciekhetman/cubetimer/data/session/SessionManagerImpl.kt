package com.maciekhetman.cubetimer.data.session

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.domain.session.AutomaticSessionHelper
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class SessionManagerImpl(
    private val sessionRepository: SessionRepository,
    private val solveDao: SolveDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SessionManager {

    private val sessionMutex = Mutex()

    override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> {
        return sessionRepository.observeActiveSessions(ownerId, mode)
            .map { sessions -> sessions.firstOrNull { it.kind == SessionKind.AUTOMATIC && it.isOpen } }
            .distinctUntilChanged()
    }

    override suspend fun getOrCreateActiveSession(
        ownerId: String,
        mode: Mode,
        solveTimestamp: Long?
    ): Session = sessionMutex.withLock {
        withContext(ioDispatcher) {
            resolveAutomaticSession(ownerId, mode, solveTimestamp ?: System.currentTimeMillis())
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
        // Fixed-width ISO string (Instant.toString() drops ".000" on whole seconds, which breaks
        // the lexicographic ordering the started_at queries rely on).
        val startedAtIso = CubeTypeConverters.epochMillisToIso(currentTimestampMs)
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
            startedAt = startedAtIso,
            endedAt = null,
            version = 0L,
            updatedAt = startedAtIso,
            deletedAt = null
        )

        return sessionRepository.createSession(newSession)
    }
}
