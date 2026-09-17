package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.domain.AverageCalculator
import com.maciekhetman.cubetimer.domain.ScrambleGenerator
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.RecordCelebration
import com.maciekhetman.cubetimer.model.RecordType
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.model.ownerId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModel(
    application: Application,
    private val repository: SolvesRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: SessionManager,
    private val authManager: AuthManager,
    private val timeSource: () -> Long = SystemClock::uptimeMillis,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    val isTimerRunning: StateFlow<Boolean> = _timerState
        .map { it is TimerState.Running }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    val dynamicColorEnabled: StateFlow<Boolean> = settingsRepository.dynamicColorEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val defaultMode: StateFlow<Mode> = settingsRepository.defaultModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Mode.CUBE_3x3)

    val amoledEnabled: StateFlow<Boolean> = settingsRepository.amoledEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showScrambleRefreshButton: StateFlow<Boolean> = settingsRepository.showScrambleRefreshButtonFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val scrambleScalePercent: StateFlow<Int> = settingsRepository.scrambleScalePercentFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 100)

    val timerStartDelayMillis: StateFlow<Int> = settingsRepository.timerStartDelayMillisFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 500)

    val timerAverages: StateFlow<Set<Int>> = settingsRepository.timerAveragesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, setOf(5, 12))

    val runningTimerDisplay: StateFlow<RunningTimerDisplay> = settingsRepository.runningTimerDisplayFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, RunningTimerDisplay.FULL)

    val hideScrambleDuringSolve: StateFlow<Boolean> = settingsRepository.hideScrambleDuringSolveFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hideAveragesDuringSolve: StateFlow<Boolean> = settingsRepository.hideAveragesDuringSolveFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hideLastResultsDuringSolve: StateFlow<Boolean> = settingsRepository.hideLastResultsDuringSolveFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hideLastResultsOnTimer: StateFlow<Boolean> = settingsRepository.hideLastResultsOnTimerFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hideStartHint: StateFlow<Boolean> = settingsRepository.hideStartHintFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val focusMode: StateFlow<Boolean> = settingsRepository.focusModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hapticsEnabled: StateFlow<Boolean> = settingsRepository.hapticsEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val hideSessionMenuInTopBar: StateFlow<Boolean> = settingsRepository.hideSessionMenuInTopBarFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Last known DB-confirmed solves per owner, used only to show the right list instantly when
    // switching owners (never merged with pending local edits - the Room flow below is always the
    // single source of truth for the active owner).
    private val confirmedSolvesByOwner = mutableMapOf<String, List<SolveTime>>()

    /**
     * A local write that the DB has not reflected yet ([solve] == null means a pending delete).
     * Pending entries let the UI update instantly, but they are always short-lived: each one is
     * dropped as soon as a DB emission proves it applied, or one emission after its write finished,
     * so a failed write can never keep a stale solve on screen indefinitely.
     */
    private class PendingWrite(val ownerId: String, val solve: SolveTime?, var settledAtEmission: Long? = null)

    private val pendingWrites = linkedMapOf<String, PendingWrite>()
    private var emissionCount = 0L

    private fun markPendingUpserts(ownerId: String, solves: List<SolveTime>) {
        solves.forEach { pendingWrites[it.id] = PendingWrite(ownerId, it) }
    }

    private fun markPendingDeletes(ownerId: String, ids: Collection<String>) {
        ids.forEach { pendingWrites[it] = PendingWrite(ownerId, null) }
    }

    private fun settlePending(ids: Collection<String>) {
        ids.forEach { id -> pendingWrites[id]?.settledAtEmission = emissionCount }
    }

    /** Drops pending writes the DB has caught up with (or that settled an emission ago). */
    private fun reconcilePending(ownerId: String, dbSolves: List<SolveTime>) {
        if (pendingWrites.isEmpty()) return
        val dbById = dbSolves.associateBy { it.id }
        val iterator = pendingWrites.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pending = entry.value
            if (pending.ownerId != ownerId) continue
            val inDb = dbById[entry.key]
            val applied = if (pending.solve == null) inDb == null else inDb == pending.solve
            val settledEarlier = pending.settledAtEmission?.let { emissionCount > it } == true
            if (applied || settledEarlier) iterator.remove()
        }
    }

    private fun mergeWithPending(ownerId: String, dbSolves: List<SolveTime>): List<SolveTime> {
        val relevant = pendingWrites.filterValues { it.ownerId == ownerId }
        if (relevant.isEmpty()) return dbSolves
        val upserts = relevant.values.mapNotNull { it.solve }
        val replacedIds = relevant.keys
        return (dbSolves.filter { it.id !in replacedIds } + upserts).sortedBy { it.timestamp }
    }

    private fun publishSolves(solves: List<SolveTime>) {
        _allSolves.value = solves
        _solves.value = solves.filter { it.mode == _currentMode.value }
    }

    private val _solves = MutableStateFlow<List<SolveTime>>(emptyList())
    val solves: StateFlow<List<SolveTime>> = _solves.asStateFlow()

    private val _allSolves = MutableStateFlow<List<SolveTime>>(emptyList())
    val allSolves: StateFlow<List<SolveTime>> = _allSolves.asStateFlow()

    private val _statsFilter = MutableStateFlow<StatsFilter>(StatsFilter.AllSessions)
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
    private var scrambleJob: Job? = null
    private var startTime: Long = 0
    private var hasAppliedDefaultMode = false
    private var inputBlockedUntil: Long = 0L

    init {
        // Migrate settings from the legacy combined datastore if needed.
        viewModelScope.launch {
            settingsRepository.migrateFromLegacyIfNeeded()
        }

        // Apply the default mode exactly once, as soon as it is known, and generate/warm up the
        // scramble for that mode (avoids generating one scramble for the initial 3x3 mode and then
        // immediately throwing it away for the real default mode).
        viewModelScope.launch {
            settingsRepository.defaultModeFlow.collect { mode ->
                if (!hasAppliedDefaultMode) {
                    hasAppliedDefaultMode = true
                    _currentMode.value = mode
                    _solves.value = _allSolves.value.filter { it.mode == mode }
                    regenerateScramble(mode)
                }
            }
        }

        // Synchronously reset in-memory state when auth owner transitions, showing the new owner's
        // last known solves instantly rather than flashing the previous owner's list.
        viewModelScope.launch {
            var lastOwnerId: String? = null
            authManager.authState.collect { authState ->
                val newOwnerId = authState.ownerId
                if (lastOwnerId != newOwnerId) {
                    val currentMode = _currentMode.value
                    val ownerSolves = confirmedSolvesByOwner[newOwnerId] ?: emptyList()
                    publishSolves(mergeWithPending(newOwnerId, ownerSolves))
                }
                lastOwnerId = newOwnerId
            }
        }

        // Reactive Room flow collector for the active owner. The DB is the single source of truth:
        // every emission fully replaces the in-memory list rather than merging it with whatever was
        // there before, so solves deleted/edited elsewhere (History screen, session cascade delete,
        // sync) are reflected instead of resurrected by a stale local copy.
        viewModelScope.launch {
            authManager.authState.flatMapLatest { authState ->
                val flowOwner = authState.ownerId
                repository.getAllSolvesFlow(flowOwner)
                    .map { dbSolves -> flowOwner to dbSolves.sortedBy { it.timestamp } }
                    .flowOn(defaultDispatcher)
            }.collect { (flowOwner, sortedSolves) ->
                confirmedSolvesByOwner[flowOwner] = sortedSolves
                emissionCount++
                reconcilePending(flowOwner, sortedSolves)
                if (flowOwner == authManager.currentOwnerId) {
                    publishSolves(mergeWithPending(flowOwner, sortedSolves))
                }
            }
        }

        // Reactive update for mode selection
        viewModelScope.launch {
            _currentMode.collect { mode ->
                _solves.value = _allSolves.value.filter { it.mode == mode }
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

    fun onPressStart() = onPressStart(timeSource())

    fun onPressStart(eventUptimeMillis: Long) {
        if (eventUptimeMillis < inputBlockedUntil) return

        when (_timerState.value) {
            is TimerState.Idle -> {
                startHoldTimer(eventUptimeMillis)
            }
            is TimerState.Running -> {
                stopTimer(eventUptimeMillis)
            }
            else -> {
                // Already holding or ready, ignore additional press.
                // Finished state has no touch handling on the timer screen.
            }
        }
    }

    fun onPressRelease() = onPressRelease(timeSource())

    fun onPressRelease(eventUptimeMillis: Long) {
        when (_timerState.value) {
            is TimerState.Holding -> {
                holdJob?.cancel()
                _timerState.value = TimerState.Idle
            }
            is TimerState.Ready -> {
                holdJob?.cancel()
                startTimer(eventUptimeMillis)
            }
            else -> {}
        }
    }

    private fun startHoldTimer(pressStartUptimeMillis: Long) {
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            val holdDuration = timerStartDelayMillis.value.toLong()
            val updateInterval = 16L // ~60fps

            while (true) {
                val elapsed = timeSource() - pressStartUptimeMillis
                if (elapsed >= holdDuration) break
                val progress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                _timerState.value = TimerState.Holding(progress)
                delay(updateInterval.milliseconds)
            }

            _timerState.value = TimerState.Ready
        }
    }

    private fun startTimer(startUptimeMillis: Long) {
        startTime = startUptimeMillis
        _timerState.value = TimerState.Running(0)

        // Pre-generate the next scramble in the background so that saving this solve shows the
        // following scramble instantly instead of waiting on TNoodle's search.
        viewModelScope.launch {
            ScrambleGenerator.warmUp(_currentMode.value)
        }

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            val updateInterval = when (runningTimerDisplay.value) {
                RunningTimerDisplay.FULL -> 16L // ~60fps, matches display refresh
                RunningTimerDisplay.SECONDS_ONLY -> 200L
                RunningTimerDisplay.HIDDEN -> return@launch // no UI updates needed
            }

            while (true) {
                delay(updateInterval.milliseconds)
                val elapsed = timeSource() - startTime
                _timerState.value = TimerState.Running(elapsed)
            }
        }
    }

    private fun stopTimer(stopUptimeMillis: Long) {
        timerJob?.cancel()
        val elapsed = stopUptimeMillis - startTime
        _timerState.value = TimerState.Finished(elapsed)
    }

    fun saveSolveWithPenalty(penalty: Penalty) {
        val currentState = _timerState.value
        if (currentState is TimerState.Finished) {
            val nowMs = System.currentTimeMillis()
            // Capture the scramble/mode synchronously: generateNewScramble() below runs
            // concurrently on another thread, and for fast puzzles (2x2/pyraminx/megaminx, scrambles
            // that generate in single-digit milliseconds) it can finish before this coroutine resumes
            // from the suspending getOrCreateActiveSession call, which would otherwise save the NEXT
            // scramble instead of the one that was actually solved.
            val currentModeValue = _currentMode.value
            val capturedScramble = _currentScramble.value
            val ownerId = authManager.currentOwnerId

            resetTimer()
            generateNewScramble()

            viewModelScope.launch {
                val activeSession = sessionManager.getOrCreateActiveSession(
                    ownerId = ownerId,
                    mode = currentModeValue,
                    solveTimestamp = nowMs
                )

                val newSolve = SolveTime(
                    timeInMillis = currentState.time,
                    penalty = penalty,
                    scramble = capturedScramble,
                    mode = currentModeValue,
                    timestamp = nowMs,
                    sessionId = activeSession.id
                )

                val newAllSolves = (_allSolves.value.filter { it.id != newSolve.id } + newSolve).sortedBy { it.timestamp }
                markPendingUpserts(ownerId, listOf(newSolve))
                if (authManager.currentOwnerId == ownerId) {
                    publishSolves(newAllSolves)
                }

                try {
                    repository.saveSolve(newSolve, ownerId = ownerId, sessionId = activeSession.id)
                } finally {
                    settlePending(listOf(newSolve.id))
                }

                // Check for records using solves for the captured mode, not whatever mode happens
                // to be selected once this coroutine resumes.
                val modeSolves = newAllSolves.filter { it.mode == currentModeValue }
                val previousSolves = modeSolves - newSolve
                checkForRecords(newSolve, previousSolves, modeSolves)
            }
        }
    }

    fun discardSolve() {
        _recordCelebration.value = null
        resetTimer()
    }

    fun generateNewScramble() {
        regenerateScramble(_currentMode.value)
    }

    private fun regenerateScramble(mode: Mode) {
        scrambleJob?.cancel()
        scrambleJob = viewModelScope.launch {
            val scramble = ScrambleGenerator.nextScramble(mode)
            _currentScramble.value = scramble
        }
    }

    private fun resetTimer() {
        timerJob?.cancel()
        holdJob?.cancel()
        _timerState.value = TimerState.Idle
    }

    fun deleteSolve(solve: SolveTime) {
        val ownerId = authManager.currentOwnerId
        markPendingDeletes(ownerId, listOf(solve.id))
        publishSolves(_allSolves.value.filter { it.id != solve.id })
        viewModelScope.launch {
            try {
                repository.deleteSolve(solve, ownerId = ownerId)
            } finally {
                settlePending(listOf(solve.id))
            }
        }
    }

    fun updateSolvePenalty(solve: SolveTime, penalty: Penalty) {
        val ownerId = authManager.currentOwnerId
        val updated = solve.copy(penalty = penalty)
        val newAllSolves = _allSolves.value.map { existing ->
            if (existing.id == solve.id) existing.copy(penalty = penalty) else existing
        }
        markPendingUpserts(ownerId, listOf(updated))
        publishSolves(newAllSolves)
        viewModelScope.launch {
            try {
                repository.updateSolvePenalty(solve, penalty, ownerId = ownerId)
            } finally {
                settlePending(listOf(solve.id))
            }
        }
    }

    fun addSolve(solve: SolveTime) {
        val ownerId = authManager.currentOwnerId
        markPendingUpserts(ownerId, listOf(solve))
        publishSolves((_allSolves.value.filter { it.id != solve.id } + solve).sortedBy { it.timestamp })
        viewModelScope.launch {
            try {
                repository.saveSolve(solve, ownerId = ownerId, sessionId = solve.sessionId)
            } finally {
                settlePending(listOf(solve.id))
            }
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
        markPendingDeletes(ownerId, toDeleteIds)
        publishSolves(_allSolves.value.filter { it.id !in toDeleteIds })
        viewModelScope.launch {
            try {
                repository.deleteSolves(toDelete, ownerId = ownerId)
            } finally {
                settlePending(toDeleteIds)
            }
        }
    }

    fun clearAllSolves() {
        val ownerId = authManager.currentOwnerId
        val clearedIds = _allSolves.value.map { it.id }
        markPendingDeletes(ownerId, clearedIds)
        publishSolves(emptyList())
        viewModelScope.launch {
            try {
                repository.clearAllSolves(ownerId = ownerId)
            } finally {
                settlePending(clearedIds)
            }
        }
    }

    fun restoreSolves(previous: List<SolveTime>) {
        val ownerId = authManager.currentOwnerId
        val toRestoreIds = previous.map { it.id }.toSet()
        markPendingUpserts(ownerId, previous)
        publishSolves((_allSolves.value.filter { it.id !in toRestoreIds } + previous).sortedBy { it.timestamp })
        viewModelScope.launch {
            try {
                repository.restoreSolves(previous, ownerId = ownerId)
            } finally {
                settlePending(toRestoreIds)
            }
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

    fun setScrambleScalePercent(percent: Int): Job = viewModelScope.launch {
        settingsRepository.setScrambleScalePercent(percent)
    }

    fun setTimerStartDelayMillis(delayMillis: Int) {
        viewModelScope.launch {
            settingsRepository.setTimerStartDelayMillis(delayMillis)
        }
    }

    fun setTimerAverageEnabled(average: Int, enabled: Boolean) {
        viewModelScope.launch {
            val updated = if (enabled) {
                timerAverages.value + average
            } else {
                timerAverages.value - average
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

    fun setHideSessionMenuInTopBar(hide: Boolean): Job = viewModelScope.launch {
        settingsRepository.setHideSessionMenuInTopBar(hide)
        if (hide) {
            val ownerId = authManager.currentOwnerId
            Mode.entries.forEach { mode ->
                sessionManager.clearManualSessionOverride(ownerId, mode)
                sessionManager.setAutomaticMode(mode, true)
            }
        }
    }

    fun setMode(mode: Mode) {
        if (_currentMode.value != mode) {
            updateAppTime()
            _currentMode.value = mode
            _solves.value = _allSolves.value.filter { it.mode == mode }
            regenerateScramble(mode)
        }
        // Load time for the new mode - it will be updated by the flow collector
    }

    fun dismissRecordCelebration() {
        _recordCelebration.value = null
        // Briefly ignore timer presses so the same tap doesn't immediately start a new solve.
        inputBlockedUntil = timeSource() + 200
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
        scrambleJob?.cancel()
    }
}
