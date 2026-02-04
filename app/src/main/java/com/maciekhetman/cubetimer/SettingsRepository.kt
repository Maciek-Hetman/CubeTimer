package com.maciekhetman.cubetimer

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val context: Context) {
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
    private val DEFAULT_MODE_KEY = stringPreferencesKey("default_mode")
    private val AMOLED_ENABLED_KEY = booleanPreferencesKey("amoled_enabled")

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
}
