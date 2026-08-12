package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.TimerAverageOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val context: Context) {
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
    private val DEFAULT_MODE_KEY = stringPreferencesKey("default_mode")
    private val AMOLED_ENABLED_KEY = booleanPreferencesKey("amoled_enabled")
    private val SHOW_SCRAMBLE_REFRESH_KEY = booleanPreferencesKey("show_scramble_refresh_button")
    private val SCRAMBLE_SCALE_PERCENT_KEY = intPreferencesKey("scramble_scale_percent")
    private val TIMER_START_DELAY_MILLIS_KEY = intPreferencesKey("timer_start_delay_millis")
    private val TIMER_AVERAGES_KEY = stringPreferencesKey("timer_averages")
    private val RUNNING_TIMER_DISPLAY_KEY = stringPreferencesKey("running_timer_display")
    private val HIDE_SCRAMBLE_DURING_SOLVE_KEY = booleanPreferencesKey("hide_scramble_during_solve")
    private val HIDE_AVERAGES_DURING_SOLVE_KEY = booleanPreferencesKey("hide_averages_during_solve")
    private val HIDE_LAST_RESULTS_DURING_SOLVE_KEY = booleanPreferencesKey("hide_last_results_during_solve")
    private val HIDE_LAST_RESULTS_ON_TIMER_KEY = booleanPreferencesKey("hide_last_results_on_timer")
    private val FOCUS_MODE_KEY = booleanPreferencesKey("focus_mode")
    private val HAPTICS_ENABLED_KEY = booleanPreferencesKey("haptics_enabled")

    /**
     * One-time migration: settings used to live in the solves datastore.
     * Copy them to the dedicated settings datastore so existing users keep their preferences.
     */
    suspend fun migrateFromLegacyIfNeeded() {
        val legacy = context.solvesDataStore.data.first()
        if (legacy.asMap().isEmpty()) return
        val current = context.settingsDataStore.data.first()
        if (current.asMap().isNotEmpty()) return

        context.settingsDataStore.edit { prefs ->
            legacy[DYNAMIC_COLOR_KEY]?.let { prefs[DYNAMIC_COLOR_KEY] = it }
            legacy[DEFAULT_MODE_KEY]?.let { prefs[DEFAULT_MODE_KEY] = it }
            legacy[AMOLED_ENABLED_KEY]?.let { prefs[AMOLED_ENABLED_KEY] = it }
            legacy[SHOW_SCRAMBLE_REFRESH_KEY]?.let { prefs[SHOW_SCRAMBLE_REFRESH_KEY] = it }
            legacy[SCRAMBLE_SCALE_PERCENT_KEY]?.let { prefs[SCRAMBLE_SCALE_PERCENT_KEY] = it }
            legacy[TIMER_START_DELAY_MILLIS_KEY]?.let { prefs[TIMER_START_DELAY_MILLIS_KEY] = it }
            legacy[TIMER_AVERAGES_KEY]?.let { prefs[TIMER_AVERAGES_KEY] = it }
            legacy[RUNNING_TIMER_DISPLAY_KEY]?.let { prefs[RUNNING_TIMER_DISPLAY_KEY] = it }
            legacy[HIDE_SCRAMBLE_DURING_SOLVE_KEY]?.let { prefs[HIDE_SCRAMBLE_DURING_SOLVE_KEY] = it }
            legacy[HIDE_AVERAGES_DURING_SOLVE_KEY]?.let { prefs[HIDE_AVERAGES_DURING_SOLVE_KEY] = it }
            legacy[HIDE_LAST_RESULTS_DURING_SOLVE_KEY]?.let { prefs[HIDE_LAST_RESULTS_DURING_SOLVE_KEY] = it }
            legacy[HIDE_LAST_RESULTS_ON_TIMER_KEY]?.let { prefs[HIDE_LAST_RESULTS_ON_TIMER_KEY] = it }
            legacy[FOCUS_MODE_KEY]?.let { prefs[FOCUS_MODE_KEY] = it }
            legacy[HAPTICS_ENABLED_KEY]?.let { prefs[HAPTICS_ENABLED_KEY] = it }
        }
    }

    val dynamicColorEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR_KEY] ?: false
    }

    val defaultModeFlow: Flow<Mode> = context.settingsDataStore.data.map { preferences ->
        val raw = preferences[DEFAULT_MODE_KEY] ?: Mode.CUBE_3x3.name
        runCatching { Mode.valueOf(raw) }.getOrDefault(Mode.CUBE_3x3)
    }

    val amoledEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[AMOLED_ENABLED_KEY] ?: false
    }

    val showScrambleRefreshButtonFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[SHOW_SCRAMBLE_REFRESH_KEY] ?: true
    }

    val scrambleScalePercentFlow: Flow<Int> = context.settingsDataStore.data.map { preferences ->
        preferences[SCRAMBLE_SCALE_PERCENT_KEY] ?: 100
    }

    val timerStartDelayMillisFlow: Flow<Int> = context.settingsDataStore.data.map { preferences ->
        preferences[TIMER_START_DELAY_MILLIS_KEY] ?: 500
    }

    val timerAveragesFlow: Flow<Set<Int>> = context.settingsDataStore.data.map { preferences ->
        val raw = preferences[TIMER_AVERAGES_KEY] ?: return@map setOf(5, 12)
        raw.split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in TimerAverageOptions }
            .toSet()
    }

    val runningTimerDisplayFlow: Flow<RunningTimerDisplay> = context.settingsDataStore.data.map { preferences ->
        val raw = preferences[RUNNING_TIMER_DISPLAY_KEY] ?: RunningTimerDisplay.FULL.name
        runCatching { RunningTimerDisplay.valueOf(raw) }.getOrDefault(RunningTimerDisplay.FULL)
    }

    val hideScrambleDuringSolveFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[HIDE_SCRAMBLE_DURING_SOLVE_KEY] ?: false
    }

    val hideAveragesDuringSolveFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[HIDE_AVERAGES_DURING_SOLVE_KEY] ?: false
    }

    val hideLastResultsDuringSolveFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[HIDE_LAST_RESULTS_DURING_SOLVE_KEY] ?: false
    }

    val hideLastResultsOnTimerFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[HIDE_LAST_RESULTS_ON_TIMER_KEY] ?: false
    }

    val focusModeFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[FOCUS_MODE_KEY] ?: false
    }

    val hapticsEnabledFlow: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[HAPTICS_ENABLED_KEY] ?: true
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_KEY] = enabled
        }
    }

    suspend fun setDefaultMode(mode: Mode) {
        context.settingsDataStore.edit { preferences ->
            preferences[DEFAULT_MODE_KEY] = mode.name
        }
    }

    suspend fun setAmoledEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[AMOLED_ENABLED_KEY] = enabled
        }
    }

    suspend fun setShowScrambleRefreshButton(show: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[SHOW_SCRAMBLE_REFRESH_KEY] = show
        }
    }

    suspend fun setScrambleScalePercent(percent: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[SCRAMBLE_SCALE_PERCENT_KEY] = percent.coerceIn(80, 140)
        }
    }

    suspend fun setTimerStartDelayMillis(delayMillis: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[TIMER_START_DELAY_MILLIS_KEY] = delayMillis.coerceIn(200, 1000)
        }
    }

    suspend fun setTimerAverages(averages: Set<Int>) {
        context.settingsDataStore.edit { preferences ->
            preferences[TIMER_AVERAGES_KEY] = TimerAverageOptions
                .filter { it in averages }
                .joinToString(",")
        }
    }

    suspend fun setRunningTimerDisplay(display: RunningTimerDisplay) {
        context.settingsDataStore.edit { preferences ->
            preferences[RUNNING_TIMER_DISPLAY_KEY] = display.name
        }
    }

    suspend fun setHideScrambleDuringSolve(hide: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[HIDE_SCRAMBLE_DURING_SOLVE_KEY] = hide
        }
    }

    suspend fun setHideAveragesDuringSolve(hide: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[HIDE_AVERAGES_DURING_SOLVE_KEY] = hide
        }
    }

    suspend fun setHideLastResultsDuringSolve(hide: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[HIDE_LAST_RESULTS_DURING_SOLVE_KEY] = hide
        }
    }

    suspend fun setHideLastResultsOnTimer(hide: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[HIDE_LAST_RESULTS_ON_TIMER_KEY] = hide
        }
    }

    suspend fun setFocusMode(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[FOCUS_MODE_KEY] = enabled
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[HAPTICS_ENABLED_KEY] = enabled
        }
    }

}
