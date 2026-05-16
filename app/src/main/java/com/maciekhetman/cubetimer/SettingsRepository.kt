package com.maciekhetman.cubetimer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val context: Context) {
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
    private val DEFAULT_MODE_KEY = stringPreferencesKey("default_mode")
    private val AMOLED_ENABLED_KEY = booleanPreferencesKey("amoled_enabled")
    private val SHOW_SCRAMBLE_REFRESH_KEY = booleanPreferencesKey("show_scramble_refresh_button")
    private val SCRAMBLE_SCALE_PERCENT_KEY = intPreferencesKey("scramble_scale_percent")
    private val TIMER_START_DELAY_MILLIS_KEY = intPreferencesKey("timer_start_delay_millis")
    private val TIMER_AVERAGES_KEY = stringPreferencesKey("timer_averages")

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

    val timerStartDelayMillisFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[TIMER_START_DELAY_MILLIS_KEY] ?: 500
    }

    val timerAveragesFlow: Flow<Set<Int>> = context.dataStore.data.map { preferences ->
        val raw = preferences[TIMER_AVERAGES_KEY] ?: "5,12"
        raw.split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in TimerAverageOptions }
            .toSet()
            .ifEmpty { setOf(5, 12) }
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

    suspend fun setTimerStartDelayMillis(delayMillis: Int) {
        context.dataStore.edit { preferences ->
            preferences[TIMER_START_DELAY_MILLIS_KEY] = delayMillis.coerceIn(200, 1000)
        }
    }

    suspend fun setTimerAverages(averages: Set<Int>) {
        context.dataStore.edit { preferences ->
            preferences[TIMER_AVERAGES_KEY] = TimerAverageOptions
                .filter { it in averages }
                .joinToString(",")
        }
    }

}

val TimerAverageOptions = listOf(5, 12, 25, 50, 100)
