package com.maciekhetman.cubetimer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository(private val context: Context) {
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
    private val DEFAULT_MODE_KEY = stringPreferencesKey("default_mode")
    private val AMOLED_ENABLED_KEY = booleanPreferencesKey("amoled_enabled")
    private val SHOW_SCRAMBLE_REFRESH_KEY = booleanPreferencesKey("show_scramble_refresh_button")
    private val SCRAMBLE_SCALE_PERCENT_KEY = intPreferencesKey("scramble_scale_percent")
    private val CUBES_KEY = stringPreferencesKey("cube_list")

    val dynamicColorEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR_KEY] ?: false
    }

    val defaultModeFlow: Flow<Mode> = context.dataStore.data.map { preferences ->
        val raw = preferences[DEFAULT_MODE_KEY] ?: Mode.CUBE_3x3.name
        runCatching { Mode.valueOf(raw) }.getOrDefault(Mode.CUBE_3x3)
    }

    val amoledEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AMOLED_ENABLED_KEY] ?: false
    }

    val showScrambleRefreshButtonFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHOW_SCRAMBLE_REFRESH_KEY] ?: true
    }

    val scrambleScalePercentFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[SCRAMBLE_SCALE_PERCENT_KEY] ?: 100
    }

    val cubesFlow: Flow<List<Cube>> = context.dataStore.data.map { preferences ->
        val json = preferences[CUBES_KEY] ?: "[]"
        parseCubes(json)
    }

    fun activeCubeIdFlow(mode: Mode): Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[activeCubeKey(mode)]
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_KEY] = enabled
        }
    }

    suspend fun setDefaultMode(mode: Mode) {
        context.dataStore.edit { preferences ->
            preferences[DEFAULT_MODE_KEY] = mode.name
        }
    }

    suspend fun setAmoledEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AMOLED_ENABLED_KEY] = enabled
        }
    }

    suspend fun setShowScrambleRefreshButton(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_SCRAMBLE_REFRESH_KEY] = show
        }
    }

    suspend fun setScrambleScalePercent(percent: Int) {
        context.dataStore.edit { preferences ->
            preferences[SCRAMBLE_SCALE_PERCENT_KEY] = percent.coerceIn(80, 140)
        }
    }

    suspend fun saveCubes(cubes: List<Cube>) {
        context.dataStore.edit { preferences ->
            preferences[CUBES_KEY] = serializeCubes(cubes)
        }
    }

    suspend fun setActiveCubeId(mode: Mode, cubeId: String?) {
        context.dataStore.edit { preferences ->
            val key = activeCubeKey(mode)
            if (cubeId.isNullOrBlank()) {
                preferences.remove(key)
            } else {
                preferences[key] = cubeId
            }
        }
    }

    private fun activeCubeKey(mode: Mode) = stringPreferencesKey("active_cube_${mode.name}")

    private fun serializeCubes(cubes: List<Cube>): String {
        val jsonArray = JSONArray()
        cubes.forEach { cube ->
            val jsonObject = JSONObject()
            jsonObject.put("id", cube.id)
            jsonObject.put("brand", cube.brand)
            jsonObject.put("model", cube.model)
            jsonObject.put("type", cube.type.name)
            jsonObject.put("createdAt", cube.createdAt)
            val featuresArray = JSONArray()
            cube.features.forEach { feature ->
                featuresArray.put(feature)
            }
            jsonObject.put("features", featuresArray)
            jsonObject.put("tension", cube.tension)
            jsonObject.put("centerTravel", cube.centerTravel)
            val lubesArray = JSONArray()
            cube.lubes.forEach { lube ->
                lubesArray.put(lube)
            }
            jsonObject.put("lubes", lubesArray)
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private fun parseCubes(json: String): List<Cube> {
        return try {
            val jsonArray = JSONArray(json)
            val cubes = mutableListOf<Cube>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val type = try {
                    Mode.valueOf(jsonObject.optString("type", "CUBE_3x3"))
                } catch (e: Exception) {
                    Mode.CUBE_3x3
                }
                val featuresJson = jsonObject.optJSONArray("features")
                val features = mutableListOf<String>()
                if (featuresJson != null) {
                    for (j in 0 until featuresJson.length()) {
                        val feature = featuresJson.optString(j, "").trim()
                        if (feature.isNotEmpty()) {
                            features.add(feature)
                        }
                    }
                }
                val lubesJson = jsonObject.optJSONArray("lubes")
                val lubes = mutableListOf<String>()
                if (lubesJson != null) {
                    for (j in 0 until lubesJson.length()) {
                        val lube = lubesJson.optString(j, "").trim()
                        if (lube.isNotEmpty()) {
                            lubes.add(lube)
                        }
                    }
                }
                cubes.add(
                    Cube(
                        id = jsonObject.optString("id", ""),
                        brand = jsonObject.optString("brand", ""),
                        model = jsonObject.optString("model", ""),
                        type = type,
                        features = features,
                        tension = jsonObject.optString("tension", ""),
                        centerTravel = jsonObject.optString("centerTravel", ""),
                        lubes = lubes,
                        createdAt = jsonObject.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            cubes.filter { it.id.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
