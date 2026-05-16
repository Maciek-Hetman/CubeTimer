package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.domain.AverageCalculator
import com.maciekhetman.cubetimer.domain.ScrambleGenerator
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.RecordCelebration
import com.maciekhetman.cubetimer.model.RecordType
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.TimerAverageOptions
import com.maciekhetman.cubetimer.model.TimerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SolvesRepository(application)
    private val settingsRepository = SettingsRepository(application)
    
    private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(true)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    private val _defaultMode = MutableStateFlow(Mode.CUBE_3x3)
    val defaultMode: StateFlow<Mode> = _defaultMode.asStateFlow()

    private val _amoledEnabled = MutableStateFlow(false)
    val amoledEnabled: StateFlow<Boolean> = _amoledEnabled.asStateFlow()

    private val _showScrambleRefreshButton = MutableStateFlow(true)
    val showScrambleRefreshButton: StateFlow<Boolean> = _showScrambleRefreshButton.asStateFlow()

    private val _scrambleScalePercent = MutableStateFlow(100)
    val scrambleScalePercent: StateFlow<Int> = _scrambleScalePercent.asStateFlow()

    private val _timerStartDelayMillis = MutableStateFlow(500)
    val timerStartDelayMillis: StateFlow<Int> = _timerStartDelayMillis.asStateFlow()

    private val _timerAverages = MutableStateFlow(setOf(5, 12))
    val timerAverages: StateFlow<Set<Int>> = _timerAverages.asStateFlow()

    private val _runningTimerDisplay = MutableStateFlow(RunningTimerDisplay.FULL)
    val runningTimerDisplay: StateFlow<RunningTimerDisplay> = _runningTimerDisplay.asStateFlow()

    private val _hideScrambleDuringSolve = MutableStateFlow(false)
    val hideScrambleDuringSolve: StateFlow<Boolean> = _hideScrambleDuringSolve.asStateFlow()

    private val _hideAveragesDuringSolve = MutableStateFlow(false)
    val hideAveragesDuringSolve: StateFlow<Boolean> = _hideAveragesDuringSolve.asStateFlow()

    private val _hideLastResultsDuringSolve = MutableStateFlow(false)
    val hideLastResultsDuringSolve: StateFlow<Boolean> = _hideLastResultsDuringSolve.asStateFlow()

    private val _hideLastResultsOnTimer = MutableStateFlow(false)
    val hideLastResultsOnTimer: StateFlow<Boolean> = _hideLastResultsOnTimer.asStateFlow()

    private val _focusMode = MutableStateFlow(false)
    val focusMode: StateFlow<Boolean> = _focusMode.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val _allSolves = MutableStateFlow<List<SolveTime>>(emptyList())
    val allSolves: StateFlow<List<SolveTime>> = _allSolves.asStateFlow()
    
    private val _solves = MutableStateFlow<List<SolveTime>>(emptyList())
    val solves: StateFlow<List<SolveTime>> = _solves.asStateFlow()
    
    private val _currentScramble = MutableStateFlow(ScrambleGenerator.generateScramble(Mode.CUBE_3x3))
    val currentScramble: StateFlow<String> = _currentScramble.asStateFlow()
    
    private val _recordCelebration = MutableStateFlow<RecordCelebration?>(null)
    val recordCelebration: StateFlow<RecordCelebration?> = _recordCelebration.asStateFlow()
    
    private val modeAppTimes = mutableMapOf<Mode, Long>()
    private val _appTimeMillis = MutableStateFlow(0L)
    val appTimeMillis: StateFlow<Long> = _appTimeMillis.asStateFlow()
    
    private var appStartTime: Long = 0L

    private var timerJob: Job? = null
    private var holdJob: Job? = null
    private var startTime: Long = 0
    private var hasAppliedDefaultMode = false

    init {
        // Load saved solves on initialization
        viewModelScope.launch {
            repository.solvesFlow.collect { savedSolves ->
                _allSolves.value = savedSolves
                _solves.value = savedSolves.filter { it.mode == _currentMode.value }
            }
        }

        // Load settings
        viewModelScope.launch {
            settingsRepository.dynamicColorEnabledFlow.collect { enabled ->
                _dynamicColorEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.defaultModeFlow.collect { mode ->
                _defaultMode.value = mode
                if (!hasAppliedDefaultMode) {
                    hasAppliedDefaultMode = true
                    setMode(mode)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.amoledEnabledFlow.collect { enabled ->
                _amoledEnabled.value = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.showScrambleRefreshButtonFlow.collect { show ->
                _showScrambleRefreshButton.value = show
            }
        }
        viewModelScope.launch {
            settingsRepository.scrambleScalePercentFlow.collect { percent ->
                _scrambleScalePercent.value = percent
            }
        }
        viewModelScope.launch {
            settingsRepository.timerStartDelayMillisFlow.collect { delayMillis ->
                _timerStartDelayMillis.value = delayMillis
            }
        }
        viewModelScope.launch {
            settingsRepository.timerAveragesFlow.collect { averages ->
                _timerAverages.value = averages
            }
        }
        viewModelScope.launch {
            settingsRepository.runningTimerDisplayFlow.collect { display ->
                _runningTimerDisplay.value = display
            }
        }
        viewModelScope.launch {
            settingsRepository.hideScrambleDuringSolveFlow.collect { hide ->
                _hideScrambleDuringSolve.value = hide
            }
        }
        viewModelScope.launch {
            settingsRepository.hideAveragesDuringSolveFlow.collect { hide ->
                _hideAveragesDuringSolve.value = hide
            }
        }
        viewModelScope.launch {
            settingsRepository.hideLastResultsDuringSolveFlow.collect { hide ->
                _hideLastResultsDuringSolve.value = hide
            }
        }
        viewModelScope.launch {
            settingsRepository.hideLastResultsOnTimerFlow.collect { hide ->
                _hideLastResultsOnTimer.value = hide
            }
        }
        viewModelScope.launch {
            settingsRepository.focusModeFlow.collect { enabled ->
                _focusMode.value = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.hapticsEnabledFlow.collect { enabled ->
                _hapticsEnabled.value = enabled
            }
        }
        // Load saved app time for the selected mode, switching collectors when the mode changes.
        viewModelScope.launch {
            _currentMode
                .flatMapLatest { mode -> repository.getAppTimeFlow(mode) }
                .collect { savedTime ->
                    val mode = _currentMode.value
                    modeAppTimes[mode] = savedTime
                    _appTimeMillis.value = savedTime
            }
        }
    }

    fun onPressStart() {
        when (val currentState = _timerState.value) {
            is TimerState.Idle -> {
                startHoldTimer()
            }
            is TimerState.Finished -> {
                // Only start hold timer if not already transitioning
                if (holdJob?.isActive != true) {
                    startHoldTimer()
                }
            }
            is TimerState.Running -> {
                stopTimer()
            }
            else -> {
                // Already holding or ready, ignore additional press
            }
        }
    }

    fun onPressRelease() {
        when (val state = _timerState.value) {
            is TimerState.Holding -> {
                holdJob?.cancel()
                _timerState.value = TimerState.Idle
            }
            is TimerState.Ready -> {
                holdJob?.cancel()
                startTimer()
            }
            else -> {}
        }
    }

    private fun startHoldTimer() {
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            val holdDuration = _timerStartDelayMillis.value.toLong()
            val updateInterval = 16L // ~60fps
            var elapsed = 0L

            while (elapsed < holdDuration) {
                delay(updateInterval)
                elapsed += updateInterval
                val progress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                _timerState.value = TimerState.Holding(progress)
            }

            _timerState.value = TimerState.Ready
        }
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        _timerState.value = TimerState.Running(0)

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(10)
                val elapsed = System.currentTimeMillis() - startTime
                _timerState.value = TimerState.Running(elapsed)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        val elapsed = System.currentTimeMillis() - startTime
        _timerState.value = TimerState.Finished(elapsed)
        
        // Check for potential record with NONE penalty
        checkForPotentialRecord(elapsed)
    }

    fun saveSolveWithPenalty(penalty: Penalty) {
        val currentState = _timerState.value
        if (currentState is TimerState.Finished) {
            val newSolve = SolveTime(
                timeInMillis = currentState.time,
                penalty = penalty,
                scramble = _currentScramble.value,
                mode = _currentMode.value
            )
            val newAllSolves = _allSolves.value + newSolve
            
            _allSolves.value = newAllSolves
            _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
            viewModelScope.launch {
                repository.saveSolves(newAllSolves)
            }
            resetTimer()
            generateNewScramble()
        }
    }

    fun discardSolve() {
        resetTimer()
    }
    
    fun generateNewScramble() {
        _currentScramble.value = ScrambleGenerator.generateScramble(_currentMode.value)
    }

    private fun resetTimer() {
        timerJob?.cancel()
        holdJob?.cancel()
        _timerState.value = TimerState.Idle
    }

    fun deleteSolve(solve: SolveTime) {
        val newAllSolves = _allSolves.value - solve
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.saveSolves(newAllSolves)
        }
    }

    fun updateSolvePenalty(solve: SolveTime, penalty: Penalty) {
        val newAllSolves = _allSolves.value.map { existing ->
            if (
                existing.timestamp == solve.timestamp &&
                existing.timeInMillis == solve.timeInMillis &&
                existing.mode == solve.mode
            ) {
                existing.copy(penalty = penalty)
            } else {
                existing
            }
        }
        if (newAllSolves == _allSolves.value) return
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.saveSolves(newAllSolves)
        }
    }

    fun exportSolvesAsCsTimerJson(): String {
        val sessionsByMode = _allSolves.value.groupBy { it.mode }
        val root = JSONObject()
        val sessionModes = JSONObject()
        var sessionIndex = 1
        sessionsByMode.forEach { (mode, modeSolves) ->
            if (modeSolves.isEmpty()) return@forEach
            val sessionKey = "session$sessionIndex"
            val sessionArray = JSONArray()
            modeSolves.sortedBy { it.timestamp }.forEach { solve ->
                val penaltyCode = when (solve.penalty) {
                    Penalty.NONE -> 0
                    Penalty.PLUS_TWO -> 1
                    Penalty.DNF -> 2
                }
                val meta = JSONArray()
                    .put(penaltyCode)
                    .put(solve.timeInMillis)
                val entry = JSONArray()
                    .put(meta)
                    .put(solve.scramble)
                    .put("")
                    .put(solve.timestamp / 1000)
                sessionArray.put(entry)
            }
            root.put(sessionKey, sessionArray)
            sessionModes.put(sessionKey, mode.name)
            sessionIndex += 1
        }
        root.put("cubetimer_session_modes", sessionModes)
        return root.toString()
    }

    fun detectCsTimerMode(json: String): Mode? {
        return runCatching {
            val root = JSONObject(json)
            detectModeFromCsTimerJson(root)
        }.getOrNull()
    }

    fun importSolvesFromCsTimerJson(
        json: String,
        fallbackMode: Mode,
        replaceExisting: Boolean
    ): Result<Int> {
        return runCatching {
            val root = JSONObject(json)
            val detectedMode = detectModeFromCsTimerJson(root)
            if (detectedMode != null && detectedMode != fallbackMode) {
                throw IllegalArgumentException(
                    "Detected ${detectedMode.displayName} data, but ${fallbackMode.displayName} was selected."
                )
            }
            val imported = mutableListOf<SolveTime>()
            val sessionModes = root.optJSONObject("cubetimer_session_modes")
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.startsWith("session")) continue
                val sessionArray = root.optJSONArray(key) ?: continue
                val sessionMode = sessionModes
                    ?.optString(key)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw -> runCatching { Mode.valueOf(raw) }.getOrNull() }
                    ?: fallbackMode
                for (i in 0 until sessionArray.length()) {
                    val entry = sessionArray.optJSONArray(i) ?: continue
                    val meta = entry.optJSONArray(0) ?: continue
                    val penaltyCode = meta.optInt(0, 0)
                    val timeMs = meta.optLong(1, 0L)
                    if (timeMs <= 0L) continue
                    val scramble = entry.optString(1, "")
                    val timestampSeconds = entry.optLong(3, System.currentTimeMillis() / 1000)
                    val penalty = when (penaltyCode) {
                        1 -> Penalty.PLUS_TWO
                        2 -> Penalty.DNF
                        else -> Penalty.NONE
                    }
                    imported.add(
                        SolveTime(
                            timeInMillis = timeMs,
                            penalty = penalty,
                            timestamp = timestampSeconds * 1000,
                            scramble = scramble,
                            mode = sessionMode
                        )
                    )
                }
            }

            if (imported.isEmpty()) return@runCatching 0

            val merged = if (replaceExisting) {
                val preserved = _allSolves.value.filter { it.mode != fallbackMode }
                preserved + imported
            } else {
                _allSolves.value + imported
            }
            val deduped = merged
                .sortedBy { it.timestamp }
                .distinctBy {
                "${it.timestamp}_${it.timeInMillis}_${it.penalty}_${it.mode}_${it.scramble}"
                }
            _allSolves.value = deduped
            _solves.value = deduped.filter { it.mode == _currentMode.value }
            viewModelScope.launch {
                repository.saveSolves(deduped)
            }
            imported.size
        }
    }

    private fun detectModeFromCsTimerJson(root: JSONObject): Mode? {
        val explicitMode = root.optString("cubetimer_mode", "")
        runCatching { Mode.valueOf(explicitMode) }.getOrNull()?.let { return it }

        val directScrType = root.optString("scrType", "")
        parseScrambleType(directScrType)?.let { return it }
        val directPuzzle = root.optString("puzzle", "")
        parseScrambleType(directPuzzle)?.let { return it }

        val candidates = mutableListOf<String>()
        collectScrTypeValues(root, candidates)
        return candidates.asSequence()
            .mapNotNull { parseScrambleType(it) }
            .firstOrNull()
    }

    private fun collectScrTypeValues(value: Any?, results: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.equals("scrType", ignoreCase = true) ||
                        key.equals("scrambleType", ignoreCase = true) ||
                        key.equals("puzzle", ignoreCase = true)
                    ) {
                        results.add(value.optString(key))
                    }
                    collectScrTypeValues(value.opt(key), results)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectScrTypeValues(value.opt(i), results)
                }
            }
        }
    }

    private fun parseScrambleType(raw: String): Mode? {
        val value = raw.lowercase(Locale.ROOT)
        return when {
            value.contains("333") || value.contains("3x3") -> Mode.CUBE_3x3
            value.contains("222") || value.contains("2x2") -> Mode.CUBE_2x2
            value.contains("444") || value.contains("4x4") -> Mode.CUBE_4x4
            value.contains("555") || value.contains("5x5") -> Mode.CUBE_5x5
            value.contains("pyr") -> Mode.PYRAMINX
            value.contains("minx") || value.contains("mega") -> Mode.MEGAMINX
            else -> null
        }
    }

    fun addSolve(solve: SolveTime) {
        val newAllSolves = (_allSolves.value + solve).sortedBy { it.timestamp }
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.saveSolves(newAllSolves)
        }
    }

    fun clearAllSolves() {
        _allSolves.value = emptyList()
        _solves.value = emptyList()
        viewModelScope.launch {
            repository.saveSolves(emptyList())
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColorEnabled(enabled)
        }
    }

    fun setDefaultMode(mode: Mode) {
        viewModelScope.launch {
            settingsRepository.setDefaultMode(mode)
        }
        setMode(mode)
    }

    fun setAmoledEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAmoledEnabled(enabled)
        }
    }

    fun setShowScrambleRefreshButton(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowScrambleRefreshButton(show)
        }
    }

    fun setScrambleScalePercent(percent: Int) {
        viewModelScope.launch {
            settingsRepository.setScrambleScalePercent(percent)
        }
    }

    fun setTimerStartDelayMillis(delayMillis: Int) {
        viewModelScope.launch {
            settingsRepository.setTimerStartDelayMillis(delayMillis)
        }
    }

    fun setTimerAverageEnabled(average: Int, enabled: Boolean) {
        viewModelScope.launch {
            val updated = if (enabled) {
                _timerAverages.value + average
            } else {
                _timerAverages.value - average
            }
            settingsRepository.setTimerAverages(updated)
        }
    }

    fun setRunningTimerDisplay(display: RunningTimerDisplay) {
        viewModelScope.launch {
            settingsRepository.setRunningTimerDisplay(display)
        }
    }

    fun setHideScrambleDuringSolve(hide: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideScrambleDuringSolve(hide)
        }
    }

    fun setHideAveragesDuringSolve(hide: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideAveragesDuringSolve(hide)
        }
    }

    fun setHideLastResultsDuringSolve(hide: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideLastResultsDuringSolve(hide)
        }
    }

    fun setHideLastResultsOnTimer(hide: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideLastResultsOnTimer(hide)
        }
    }

    fun setFocusMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFocusMode(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHapticsEnabled(enabled)
        }
    }

    fun setMode(mode: Mode) {
        _currentMode.value = mode
        _solves.value = _allSolves.value.filter { it.mode == mode }
        generateNewScramble()
        // Load time for the new mode - it will be updated by the flow collector
    }
    
    fun dismissRecordCelebration() {
        viewModelScope.launch {
            _recordCelebration.value = null
            // Small delay to prevent touch from immediately triggering timer
            delay(200)
        }
    }
    
    private fun checkForPotentialRecord(timeInMillis: Long) {
        // Simulate a solve with no penalty to check if it would be a record
        val potentialSolve = SolveTime(
            timeInMillis = timeInMillis,
            penalty = Penalty.NONE,
            scramble = _currentScramble.value
        )
        val currentSolves = _solves.value
        val potentialSolves = currentSolves + potentialSolve
        
        checkForRecords(potentialSolve, currentSolves, potentialSolves)
    }
    
    private fun checkForRecords(
        newSolve: SolveTime,
        previousSolves: List<SolveTime>,
        newSolves: List<SolveTime>
    ) {
        if (newSolve.penalty == Penalty.DNF) return
        
        // Check for best single
        val previousBest = previousSolves
            .filter { it.penalty != Penalty.DNF }
            .minOfOrNull { it.displayTime }
        
        if (previousBest == null || newSolve.displayTime < previousBest) {
            _recordCelebration.value = RecordCelebration(
                type = RecordType.BEST_SINGLE,
                time = newSolve.displayTime
            )
            return
        }
        
        // Check for best Ao5
        if (newSolves.size >= 5) {
            val currentAo5 = AverageCalculator.averageOfN(newSolves, 5)
            val previousBestAo5 = AverageCalculator.bestAverageOfN(previousSolves, 5)
            
            if (currentAo5 != null && (previousBestAo5 == null || currentAo5 < previousBestAo5)) {
                _recordCelebration.value = RecordCelebration(
                    type = RecordType.BEST_AO5,
                    time = currentAo5
                )
                return
            }
        }
        
        // Check for best Ao12
        if (newSolves.size >= 12) {
            val currentAo12 = AverageCalculator.averageOfN(newSolves, 12)
            val previousBestAo12 = AverageCalculator.bestAverageOfN(previousSolves, 12)
            
            if (currentAo12 != null && (previousBestAo12 == null || currentAo12 < previousBestAo12)) {
                _recordCelebration.value = RecordCelebration(
                    type = RecordType.BEST_AO12,
                    time = currentAo12
                )
            }
        }
    }
    fun resetAppStartTime() {
        appStartTime = System.currentTimeMillis()
    }
    
    fun updateAppTime() {
        if (appStartTime == 0L) return // Don't update if we haven't started tracking yet
        
        val currentTime = System.currentTimeMillis()
        val sessionTime = currentTime - appStartTime
        val currentMode = _currentMode.value
        val savedTime = modeAppTimes[currentMode] ?: 0L
        val newTotalTime = savedTime + sessionTime
        modeAppTimes[currentMode] = newTotalTime
        _appTimeMillis.value = newTotalTime
        appStartTime = currentTime
        
        viewModelScope.launch {
            repository.saveAppTime(currentMode, newTotalTime)
        }
    }

    override fun onCleared() {
        super.onCleared()
        updateAppTime()
        timerJob?.cancel()
        holdJob?.cancel()
    }
}
