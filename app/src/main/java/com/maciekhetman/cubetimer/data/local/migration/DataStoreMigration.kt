package com.maciekhetman.cubetimer.data.local.migration

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.domain.session.AutomaticSessionHelper
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.SessionKind
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DataStoreMigration(
    private val context: Context,
    private val database: CubeDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    companion object {
        private const val TAG = "DataStoreMigration"
        val DATASTORE_SOLVES_MIGRATED_KEY = booleanPreferencesKey("datastore_solves_migrated")
        private val SOLVES_LIST_KEY = stringPreferencesKey("solves_list")
        private val migrationMutex = Mutex()
    }

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            val settings = context.settingsDataStore.data.first()
            if (settings[DATASTORE_SOLVES_MIGRATED_KEY] != true) {
                importLegacySolves()
            }
            // Runs on every start, not only right after an import: earlier builds imported the
            // legacy solves without a session, and those installs are already marked migrated.
            // Best effort: callers fire this and forget it, and the next start simply retries.
            try {
                attachSessionlessGuestSolves()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not attach session-less guest solves", e)
            }
        }
    }

    private suspend fun importLegacySolves() {
        val rawJson = context.solvesDataStore.data.first()[SOLVES_LIST_KEY]

        if (!rawJson.isNullOrBlank() && rawJson.trim() != "[]") {
            val entities = parseLegacySolvesJson(rawJson)
            if (entities.isNotEmpty()) {
                database.withTransaction {
                    database.solveDao().upsertAll(entities)
                    attachSessionlessGuestSolves()
                }
            }
        }

        markMigrated()
    }

    /**
     * History lists solves by session, so a guest solve without one is invisible there. Such
     * solves (the legacy import's, or any left behind by older builds) are grouped per event
     * into runs split at the automatic-session inactivity gap, and each run gets the closed
     * automatic session the app would have opened for it. Guest rows have no outbox, and
     * signed-in owners are left alone: the server accepts session-less solves, and re-homing
     * them here would rewrite the server's copy.
     */
    private suspend fun attachSessionlessGuestSolves() = database.withTransaction {
        val orphans = database.solveDao().getActiveSolvesWithoutSession("guest")
        if (orphans.isEmpty()) return@withTransaction

        val sessionDao = database.sessionDao()
        val activeSessions = sessionDao.getAllActiveSessionsForOwner("guest")
        val nowIso = CubeTypeConverters.nowIso()
        val newSessions = ArrayList<SessionEntity>()
        val attachedSolves = ArrayList<SolveEntity>(orphans.size)

        orphans.groupBy { it.event }.forEach { (event, eventSolves) ->
            val takenNames = activeSessions.filter { it.event == event }.mapTo(ArrayList()) { it.name }
            val timed = eventSolves
                .map { CubeTypeConverters.isoToEpochMillis(it.solvedAt) to it }
                .sortedBy { it.first }

            var runStart = 0
            for (i in timed.indices) {
                val runEnds = i == timed.lastIndex ||
                    timed[i + 1].first - timed[i].first > AutomaticSessionHelper.DEFAULT_INACTIVITY_GAP_MILLIS
                if (!runEnds) continue

                val run = timed.subList(runStart, i + 1)
                val firstMs = run.first().first
                val name = AutomaticSessionHelper.uniqueAutomaticSessionName(
                    Instant.ofEpochMilli(firstMs), takenNames, zoneId
                )
                takenNames += name
                val session = SessionEntity(
                    id = UUID.randomUUID().toString(),
                    ownerId = "guest",
                    name = name,
                    event = event,
                    // Closed (ended_at = last solve), so the automatic session policy never
                    // picks one up as the open session to append to.
                    kind = SessionKind.AUTOMATIC.value,
                    startedAt = CubeTypeConverters.epochMillisToIso(firstMs),
                    endedAt = CubeTypeConverters.epochMillisToIso(run.last().first),
                    archived = false,
                    version = 0L,
                    updatedAt = nowIso,
                    deletedAt = null
                )
                newSessions += session
                run.mapTo(attachedSolves) { (_, solve) -> solve.copy(sessionId = session.id) }
                runStart = i + 1
            }
        }

        sessionDao.insertAll(newSessions)
        database.solveDao().upsertAll(attachedSolves)
    }

    private suspend fun markMigrated() {
        context.settingsDataStore.edit { preferences ->
            preferences[DATASTORE_SOLVES_MIGRATED_KEY] = true
        }
    }

    fun parseLegacySolvesJson(rawJson: String): List<SolveEntity> {
        return try {
            val jsonArray = JSONArray(rawJson)
            val list = ArrayList<SolveEntity>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val timeInMillis = obj.optLong("timeInMillis", 0L).coerceAtLeast(0L)
                val penaltyRaw = obj.optString("penalty", "NONE")
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val scramble = obj.optString("scramble", "")
                val modeRaw = obj.optString("mode", "CUBE_3x3")

                val event = mapModeNameToEvent(modeRaw)
                val penaltyDb = mapPenaltyNameToDb(penaltyRaw)
                val isoSolvedAt = CubeTypeConverters.epochMillisToIso(timestamp)

                list.add(
                    SolveEntity(
                        id = id,
                        ownerId = "guest",
                        sessionId = null,
                        event = event,
                        durationMs = timeInMillis,
                        penalty = penaltyDb,
                        solvedAt = isoSolvedAt,
                        scramble = scramble,
                        version = 0L,
                        updatedAt = isoSolvedAt,
                        deletedAt = null
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun mapModeNameToEvent(modeRaw: String): String {
        val mode = when (val name = modeRaw.trim()) {
            // The oldest builds stored a cube mode as its bare size ("3"), which the converter doesn't know.
            "2" -> Mode.CUBE_2x2
            "3" -> Mode.CUBE_3x3
            "4" -> Mode.CUBE_4x4
            "5" -> Mode.CUBE_5x5
            else -> CubeTypeConverters.toMode(name)
        }
        return CubeTypeConverters.fromMode(mode)
    }

    private fun mapPenaltyNameToDb(penaltyRaw: String): String =
        CubeTypeConverters.fromPenalty(CubeTypeConverters.toPenalty(penaltyRaw))
}
