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
import com.maciekhetman.cubetimer.model.TimerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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
        when (_timerState.value) {
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
        when (_timerState.value) {
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
                delay(updateInterval.milliseconds)
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
                delay(10.milliseconds)
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
        if (_currentMode.value != mode) {
            updateAppTime()
            _currentMode.value = mode
            _solves.value = _allSolves.value.filter { it.mode == mode }
            generateNewScramble()
        }
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
            scramble = _currentScramble.value,
            mode = _currentMode.value
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
