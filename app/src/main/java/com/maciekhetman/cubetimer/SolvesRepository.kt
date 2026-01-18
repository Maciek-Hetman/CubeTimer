package com.maciekhetman.cubetimer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "solves")

class SolvesRepository(private val context: Context) {
    private val SOLVES_KEY = stringPreferencesKey("solves_list")

    val solvesFlow: Flow<List<SolveTime>> = context.dataStore.data.map { preferences ->
        val json = preferences[SOLVES_KEY] ?: "[]"
        parseSolves(json)
    }

    suspend fun saveSolves(solves: List<SolveTime>) {
        context.dataStore.edit { preferences ->
            preferences[SOLVES_KEY] = serializeSolves(solves)
        }
    }

    private fun serializeSolves(solves: List<SolveTime>): String {
        val jsonArray = JSONArray()
        solves.forEach { solve ->
            val jsonObject = JSONObject()
            jsonObject.put("timeInMillis", solve.timeInMillis)
            jsonObject.put("penalty", solve.penalty.name)
            jsonObject.put("timestamp", solve.timestamp)
            jsonObject.put("scramble", solve.scramble)
            jsonObject.put("mode", solve.mode.name)
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private fun parseSolves(json: String): List<SolveTime> {
        return try {
            val jsonArray = JSONArray(json)
            val solves = mutableListOf<SolveTime>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                solves.add(
                    SolveTime(
                        timeInMillis = jsonObject.getLong("timeInMillis"),
                        penalty = Penalty.valueOf(jsonObject.getString("penalty")),
                        timestamp = jsonObject.getLong("timestamp"),
                        scramble = jsonObject.optString("scramble", ""),
                        mode = try {
                            Mode.valueOf(jsonObject.optString("mode", "CUBE_3x3"))
                        } catch (e: Exception) {
                            Mode.CUBE_3x3
                        }
                    )
                )
            }
            solves
        } catch (e: Exception) {
            emptyList()
        }
    }
}
