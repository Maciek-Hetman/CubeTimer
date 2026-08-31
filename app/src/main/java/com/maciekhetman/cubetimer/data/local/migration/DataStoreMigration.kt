package com.maciekhetman.cubetimer.data.local.migration

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DataStoreMigration(
    private val context: Context,
    private val database: CubeDatabase
) {
    companion object {
        val DATASTORE_SOLVES_MIGRATED_KEY = booleanPreferencesKey("datastore_solves_migrated")
        private val SOLVES_LIST_KEY = stringPreferencesKey("solves_list")
        private val migrationMutex = Mutex()
    }

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        migrationMutex.withLock {
            val settings = context.settingsDataStore.data.first()
            if (settings[DATASTORE_SOLVES_MIGRATED_KEY] == true) {
                return@withLock
            }

            val solvesPrefs = context.solvesDataStore.data.first()
            val rawJson = solvesPrefs[SOLVES_LIST_KEY]

            if (rawJson.isNullOrBlank() || rawJson.trim() == "[]") {
                markMigrated()
                return@withLock
            }

            val entities = parseLegacySolvesJson(rawJson)

            if (entities.isNotEmpty()) {
                database.withTransaction {
                    database.solveDao().upsertAll(entities)
                }
            }

            markMigrated()
        }
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
        return when (modeRaw.uppercase().trim()) {
            "CUBE_2X2", "2X2", "2" -> "2x2"
            "CUBE_3X3", "3X3", "3" -> "3x3"
            "CUBE_4X4", "4X4", "4" -> "4x4"
            "CUBE_5X5", "5X5", "5" -> "5x5"
            "MEGAMINX" -> "megaminx"
            "PYRAMINX" -> "pyraminx"
            else -> "3x3"
        }
    }

    private fun mapPenaltyNameToDb(penaltyRaw: String): String {
        return when (penaltyRaw.uppercase().trim()) {
            "NONE" -> "none"
            "PLUS_TWO", "+2", "PLUS2" -> "plus_two"
            "DNF" -> "dnf"
            else -> "none"
        }
    }
}
