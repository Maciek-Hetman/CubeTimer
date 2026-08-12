package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.SolveTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SolvesRepository(private val context: Context) {
    private val SOLVES_KEY = stringPreferencesKey("solves_list")

    val solvesFlow: Flow<List<SolveTime>> = context.solvesDataStore.data
        .map { preferences -> preferences[SOLVES_KEY] ?: "[]" }
        .distinctUntilChanged()
        .map { json -> parseSolves(json) }
        .flowOn(Dispatchers.Default)

    fun getAppTimeFlow(mode: Mode): Flow<Long> = context.solvesDataStore.data
        .map { preferences ->
            val key = longPreferencesKey("app_time_${mode.name}")
            preferences[key] ?: 0L
        }
        .distinctUntilChanged()

    suspend fun saveSolves(solves: List<SolveTime>) {
        val json = withContext(Dispatchers.Default) { serializeSolves(solves) }
        context.solvesDataStore.edit { preferences ->
            preferences[SOLVES_KEY] = json
        }
    }

    suspend fun saveAppTime(mode: Mode, timeMillis: Long) {
        context.solvesDataStore.edit { preferences ->
            val key = longPreferencesKey("app_time_${mode.name}")
            preferences[key] = timeMillis
        }
    }

    private fun serializeSolves(solves: List<SolveTime>): String {
        val jsonArray = JSONArray()
        solves.forEach { solve ->
            val jsonObject = JSONObject()
            jsonObject.put("id", solve.id)
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
                        id = jsonObject.optString("id", UUID.randomUUID().toString()),
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
