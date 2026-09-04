package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.domain.AverageCalculator
import com.maciekhetman.cubetimer.domain.ScrambleGenerator
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.RecordCelebration
import com.maciekhetman.cubetimer.model.RecordType
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.TimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthManagerImpl
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.ownerId
import com.maciekhetman.cubetimer.model.Session
import kotlinx.coroutines.flow.combine

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModel(
    application: Application,
    private val repository: SolvesRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: SessionManager,
    private val authManager: AuthManager
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = SolvesRepository(application),
        settingsRepository = SettingsRepository(application),
        sessionManager = SessionManagerImpl(
            context = application,
            sessionRepository = SessionRepositoryImpl(CubeDatabase.getInstance(application)),
            solveDao = CubeDatabase.getInstance(application).solveDao(),
            authManager = AuthManagerImpl.getInstance(application)
        ),
        authManager = AuthManagerImpl.getInstance(application)
    )

    private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    val isTimerRunning: StateFlow<Boolean> = _timerState
        .map { it is TimerState.Running }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    private val _hideStartHint = MutableStateFlow(false)
    val hideStartHint: StateFlow<Boolean> = _hideStartHint.asStateFlow()

    private val _focusMode = MutableStateFlow(false)
    val focusMode: StateFlow<Boolean> = _focusMode.asStateFlow()

    private val _hapticsEnabled = MutableStateFlow(true)
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    private val solvesByOwner = mutableMapOf<String, List<SolveTime>>()
    private val pendingDeletedIds = mutableSetOf<String>()

    private val _solves = MutableStateFlow<List<SolveTime>>(emptyList())
    val solves: StateFlow<List<SolveTime>> = _solves.asStateFlow()

    private val _allSolves = MutableStateFlow<List<SolveTime>>(emptyList())
    val allSolves: StateFlow<List<SolveTime>> = _allSolves.asStateFlow()

    private val _statsFilter = MutableStateFlow<StatsFilter>(StatsFilter.ActiveSession)
    val statsFilter: StateFlow<StatsFilter> = _statsFilter.asStateFlow()

    /**
     * Active session for the currently selected Mode.
     */
    val activeSession: StateFlow<Session?> = combine(_currentMode, authManager.authState) { mode, authState ->
        Pair(authState.ownerId, mode)
    }.flatMapLatest { (ownerId, mode) ->
        sessionManager.getActiveSessionFlow(ownerId, mode)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Solves filtered for StatsScreen based on the selected StatsFilter (ActiveSession, AllSessions, SpecificSession).
     */
    val statsFilteredSolves: StateFlow<List<SolveTime>> = combine(
        _solves,
        activeSession,
        _statsFilter
    ) { modeSolves, activeSes, filter ->
        when (filter) {
            is StatsFilter.ActiveSession -> {
                val activeId = activeSes?.id
                if (activeId != null) {
                    modeSolves.filter { it.sessionId == activeId }
                } else {
                    modeSolves
                }
            }
            is StatsFilter.AllSessions -> modeSolves
            is StatsFilter.SpecificSession -> modeSolves.filter { it.sessionId == filter.sessionId }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentScramble = MutableStateFlow("")
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
    private var inputBlockedUntil: Long = 0L

    init {
        // Generate the first scramble off the main thread (table initialization can be expensive).
        viewModelScope.launch(Dispatchers.Default) {
            val mode = _currentMode.value
            _currentScramble.value = ScrambleGenerator.generateScramble(mode)
        }

        // Migrate settings from the legacy combined datastore if needed.
        viewModelScope.launch {
            settingsRepository.migrateFromLegacyIfNeeded()
        }

        // Synchronously reset and reload in-memory state when auth owner transitions
        viewModelScope.launch {
            var lastOwnerId: String? = null
            authManager.authState.collect { authState ->
                val newOwnerId = authState.ownerId
                if (lastOwnerId != newOwnerId) {
                    val currentMode = _currentMode.value
                    val ownerSolves = solvesByOwner[newOwnerId] ?: emptyList()
                    _allSolves.value = ownerSolves
                    _solves.value = ownerSolves.filter { it.mode == currentMode }
                }
                lastOwnerId = newOwnerId
            }
        }

        // Reactive Room flow collector for the active owner
        viewModelScope.launch {
            authManager.authState.flatMapLatest { authState ->
                val flowOwner = authState.ownerId
                repository.getAllSolvesFlow(flowOwner).map { dbSolves -> Pair(flowOwner, dbSolves) }
            }.collect { (flowOwner, dbSolves) ->
                if (flowOwner == authManager.currentOwnerId) {
                    val currentOwnerSolves = solvesByOwner[flowOwner] ?: emptyList()
                    val activeDbSolves = dbSolves.filter { it.id !in pendingDeletedIds }
                    val dbMap = activeDbSolves.associateBy { it.id }
                    val pendingLocal = currentOwnerSolves.filter { it.id !in dbMap && it.id !in pendingDeletedIds }
                    val currentMap = currentOwnerSolves.associateBy { it.id }
                    val reconciledDb = activeDbSolves.map { dbSolve ->
                        val local = currentMap[dbSolve.id]
                        if (local != null && local.penalty != dbSolve.penalty) {
                            local
                        } else {
                            dbSolve
                        }
                    }
                    val merged = (reconciledDb + pendingLocal).sortedBy { it.timestamp }
                    solvesByOwner[flowOwner] = merged
                    _allSolves.value = merged
                    _solves.value = merged.filter { it.mode == _currentMode.value }
                }
            }
        }

        // Reactive update for mode selection
        viewModelScope.launch {
            _currentMode.collect { mode ->
                _solves.value = _allSolves.value.filter { it.mode == mode }
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
            settingsRepository.hideStartHintFlow.collect { hide ->
                _hideStartHint.value = hide
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
        if (System.currentTimeMillis() < inputBlockedUntil) return

        when (_timerState.value) {
            is TimerState.Idle -> {
                startHoldTimer()
            }
            is TimerState.Running -> {
                stopTimer()
            }
            else -> {
                // Already holding or ready, ignore additional press.
                // Finished state has no touch handling on the timer screen.
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
            val updateInterval = when (_runningTimerDisplay.value) {
                RunningTimerDisplay.FULL -> 10L
                RunningTimerDisplay.SECONDS_ONLY -> 200L
                RunningTimerDisplay.HIDDEN -> return@launch // no UI updates needed
            }

            while (true) {
                delay(updateInterval.milliseconds)
                val elapsed = System.currentTimeMillis() - startTime
                _timerState.value = TimerState.Running(elapsed)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        val elapsed = System.currentTimeMillis() - startTime
        _timerState.value = TimerState.Finished(elapsed)
    }

    fun saveSolveWithPenalty(penalty: Penalty) {
        val currentState = _timerState.value
        if (currentState is TimerState.Finished) {
            val nowMs = System.currentTimeMillis()
            val currentModeValue = _currentMode.value
            val ownerId = authManager.currentOwnerId

            viewModelScope.launch {
                val activeSession = sessionManager.getOrCreateActiveSession(
                    ownerId = ownerId,
                    mode = currentModeValue,
                    solveTimestamp = nowMs
                )

                val newSolve = SolveTime(
                    timeInMillis = currentState.time,
                    penalty = penalty,
                    scramble = _currentScramble.value,
                    mode = currentModeValue,
                    timestamp = nowMs,
                    sessionId = activeSession.id
                )

                val currentForOwner = solvesByOwner[ownerId] ?: emptyList()
                val newAllSolves = (currentForOwner.filter { it.id != newSolve.id } + newSolve).sortedBy { it.timestamp }
                solvesByOwner[ownerId] = newAllSolves
                pendingDeletedIds.remove(newSolve.id)
                if (authManager.currentOwnerId == ownerId) {
                    _allSolves.value = newAllSolves
                    _solves.value = newAllSolves.filter { it.mode == currentModeValue }
                }

                repository.saveSolve(newSolve, ownerId = ownerId, sessionId = activeSession.id)

                // Check for records using the actual saved penalty.
                val previousSolves = _solves.value - newSolve
                checkForRecords(newSolve, previousSolves, _solves.value)
            }

            resetTimer()
            generateNewScramble()
        }
    }

    fun discardSolve() {
        _recordCelebration.value = null
        resetTimer()
    }

    fun generateNewScramble() {
        val mode = _currentMode.value
        viewModelScope.launch(Dispatchers.Default) {
            val scramble = ScrambleGenerator.generateScramble(mode)
            // Guard against out-of-order results from rapid mode switches.
            if (_currentMode.value == mode) {
                _currentScramble.value = scramble
            }
        }
    }

    private fun resetTimer() {
        timerJob?.cancel()
        holdJob?.cancel()
        _timerState.value = TimerState.Idle
    }

    fun deleteSolve(solve: SolveTime) {
        val ownerId = authManager.currentOwnerId
        pendingDeletedIds.add(solve.id)
        val currentForOwner = solvesByOwner[ownerId] ?: _allSolves.value
        val newAllSolves = currentForOwner.filter { it.id != solve.id }
        solvesByOwner[ownerId] = newAllSolves
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.deleteSolve(solve, ownerId = ownerId)
        }
    }

    fun updateSolvePenalty(solve: SolveTime, penalty: Penalty) {
        val ownerId = authManager.currentOwnerId
        val currentForOwner = solvesByOwner[ownerId] ?: _allSolves.value
        val newAllSolves = currentForOwner.map { existing ->
            if (existing.id == solve.id) existing.copy(penalty = penalty) else existing
        }
        solvesByOwner[ownerId] = newAllSolves
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.updateSolvePenalty(solve, penalty, ownerId = ownerId)
        }
    }

    fun addSolve(solve: SolveTime) {
        val ownerId = authManager.currentOwnerId
        pendingDeletedIds.remove(solve.id)
        val currentForOwner = solvesByOwner[ownerId] ?: _allSolves.value
        val newAllSolves = (currentForOwner.filter { it.id != solve.id } + solve).sortedBy { it.timestamp }
        solvesByOwner[ownerId] = newAllSolves
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.saveSolve(solve, ownerId = ownerId, sessionId = solve.sessionId)
        }
    }

    fun setStatsFilter(filter: StatsFilter) {
        _statsFilter.value = filter
    }

    fun clearFilteredSolves() {
        val toDelete = statsFilteredSolves.value
        if (toDelete.isEmpty()) return
        val ownerId = authManager.currentOwnerId
        val toDeleteIds = toDelete.map { it.id }.toSet()
        pendingDeletedIds.addAll(toDeleteIds)
        val currentForOwner = solvesByOwner[ownerId] ?: _allSolves.value
        val newAllSolves = currentForOwner.filter { it.id !in toDeleteIds }
        solvesByOwner[ownerId] = newAllSolves
        _allSolves.value = newAllSolves
        _solves.value = newAllSolves.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.deleteSolves(toDelete, ownerId = ownerId)
        }
    }

    fun clearAllSolves() {
        val ownerId = authManager.currentOwnerId
        pendingDeletedIds.addAll(_allSolves.value.map { it.id })
        solvesByOwner[ownerId] = emptyList()
        _allSolves.value = emptyList()
        _solves.value = emptyList()
        viewModelScope.launch {
            repository.clearAllSolves(ownerId = ownerId)
        }
    }

    fun restoreSolves(previous: List<SolveTime>) {
        val ownerId = authManager.currentOwnerId
        val toRestoreIds = previous.map { it.id }.toSet()
        pendingDeletedIds.removeAll(toRestoreIds)
        val currentForOwner = solvesByOwner[ownerId] ?: _allSolves.value
        val merged = (currentForOwner.filter { it.id !in toRestoreIds } + previous).sortedBy { it.timestamp }
        solvesByOwner[ownerId] = merged
        _allSolves.value = merged
        _solves.value = merged.filter { it.mode == _currentMode.value }
        viewModelScope.launch {
            repository.restoreSolves(previous, ownerId = ownerId)
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

    fun setHideStartHint(hide: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHideStartHint(hide)
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
        _recordCelebration.value = null
        // Briefly ignore timer presses so the same tap doesn't immediately start a new solve.
        inputBlockedUntil = System.currentTimeMillis() + 200
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
        timerJob?.cancel()
        holdJob?.cancel()
    }
}
