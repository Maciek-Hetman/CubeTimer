package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerManager
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerState
import com.maciekhetman.cubetimer.data.bluetooth.BluetoothTimerStatus
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.domain.AverageCalculator
import com.maciekhetman.cubetimer.domain.ScrambleGenerator
import com.maciekhetman.cubetimer.domain.bluetooth.SmartTimerEvent
import com.maciekhetman.cubetimer.model.Inspection
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.RecordCelebration
import com.maciekhetman.cubetimer.model.RecordType
import com.maciekhetman.cubetimer.model.RunningTimerDisplay
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.model.TimingDevice
import com.maciekhetman.cubetimer.model.ownerId
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModel(
    application: Application,
    private val repository: SolvesRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: SessionManager,
    private val authManager: AuthManager,
    private val timeSource: () -> Long = SystemClock::uptimeMillis,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val bluetoothTimer: BluetoothTimerManager? = null
) : AndroidViewModel(application) {

    private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    val isTimerRunning: StateFlow<Boolean> = _timerState
        .map { it is TimerState.Running }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while a solve is running or inspection is counting down: mode, device and navigation stay locked. */
    val isTimerBusy: StateFlow<Boolean> = _timerState
        .map { it is TimerState.Running || it is TimerState.Inspecting }
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

    val inspectionEnabled: StateFlow<Boolean> = settingsRepository.inspectionEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Touch timing, or a Bluetooth timer driving the timer instead of the screen. */
    val timingDevice: StateFlow<TimingDevice> = settingsRepository.timingDeviceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimingDevice.KEYBOARD)

    val bluetoothTimerState: StateFlow<BluetoothTimerState> = bluetoothTimer?.state
        ?: MutableStateFlow(BluetoothTimerState(status = BluetoothTimerStatus.Unsupported)).asStateFlow()

    val bluetoothPermissions: List<String> = bluetoothTimer?.requiredPermissions.orEmpty()

    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

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

    /**
     * Runs the Room write behind an optimistic update of [ids]. A failure (e.g. a full disk) is
     * rolled back on screen and reported through [writeError] instead of escaping viewModelScope
     * and crashing the app.
     */
    private fun launchWrite(
        ownerId: String,
        ids: Collection<String>,
        errorMessage: String,
        write: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                write()
                settlePending(ids)
            } catch (e: CancellationException) {
                settlePending(ids)
                throw e
            } catch (e: Exception) {
                onWriteFailed(ownerId, ids, errorMessage, e)
            }
        }
    }

    /**
     * Drops the failed writes' pending entries and re-shows the last DB state right away: a write
     * that never reached the DB produces no emission, so the optimistic change would otherwise stay
     * on screen until some unrelated write.
     */
    private fun onWriteFailed(ownerId: String, ids: Collection<String>, message: String, error: Exception) {
        Log.e(TAG, message, error)
        ids.forEach { pendingWrites.remove(it) }
        if (ownerId == authManager.currentOwnerId) {
            confirmedSolvesByOwner[ownerId]?.let { publishSolves(mergeWithPending(ownerId, it)) }
        }
        _writeError.value = message
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

    // Inspection bookkeeping (touch input only). The ticker publishes the elapsed inspection time and
    // the hold progress; the penalty is always computed from the press/release timestamps instead.
    private var inspectionJob: Job? = null
    private var inspectionStartTime: Long = 0
    private var inspectionHoldStart: Long? = null
    private var inspectionHoldDuration: Long = 0
    /** The press that began inspection is still down; its release must not start anything. */
    private var inspectionStartPressDown = false
    private var activeInspectionPenalty: Penalty = Penalty.NONE

    /** Puzzle and scramble captured when the solve started, so a later refresh or mode change cannot be saved with it. */
    private var activeSolveMode: Mode? = null
    private var activeSolveScramble: String? = null
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
                // SQL orders by the solved_at *string*, which misorders solves within a second when
                // server rows omit milliseconds; sorting by epoch millis is cheap on nearly-sorted input.
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

        // A connected Bluetooth timer drives the timer only while it is the selected input.
        bluetoothTimer?.let { manager ->
            viewModelScope.launch {
                timingDevice
                    .flatMapLatest { device ->
                        if (device == TimingDevice.EXTERNAL_TIMER) manager.events else emptyFlow()
                    }
                    .collect { onSmartTimerEvent(it) }
            }
        }
    }

    fun onPressStart() = onPressStart(timeSource())

    fun onPressStart(eventUptimeMillis: Long) {
        if (eventUptimeMillis < inputBlockedUntil) return
        // With a Bluetooth timer selected the screen is not a timer input.
        if (timingDevice.value == TimingDevice.EXTERNAL_TIMER) return

        when (_timerState.value) {
            is TimerState.Idle -> {
                if (inspectionEnabled.value) {
                    startInspection(eventUptimeMillis)
                } else {
                    startHoldTimer(eventUptimeMillis)
                }
            }
            is TimerState.Inspecting -> {
                // A new press means the release of the one that began inspection was lost.
                inspectionStartPressDown = false
                if (inspectionHoldStart == null) startInspectionHold(eventUptimeMillis)
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
        if (timingDevice.value == TimingDevice.EXTERNAL_TIMER) return
        when (_timerState.value) {
            is TimerState.Holding -> {
                holdJob?.cancel()
                _timerState.value = TimerState.Idle
            }
            is TimerState.Ready -> {
                holdJob?.cancel()
                startTimer(eventUptimeMillis)
            }
            is TimerState.Inspecting -> onInspectionRelease(eventUptimeMillis)
            else -> {}
        }
    }

    private fun startInspection(pressUptimeMillis: Long) {
        clearInspection()
        inspectionStartTime = pressUptimeMillis
        // The finger that began inspection is still down; its release does nothing.
        inspectionStartPressDown = true
        _timerState.value = TimerState.Inspecting(elapsedMillis = 0)
        inspectionJob = viewModelScope.launch {
            while (true) {
                // Finer ticks only while the hold progress bar is animating.
                delay((if (inspectionHoldStart != null) 16L else 50L).milliseconds)
                publishInspection(timeSource())
            }
        }
    }

    private fun startInspectionHold(pressUptimeMillis: Long) {
        inspectionHoldStart = pressUptimeMillis
        inspectionHoldDuration = timerStartDelayMillis.value.toLong()
        publishInspection(pressUptimeMillis)
    }

    private fun publishInspection(nowMillis: Long) {
        if (_timerState.value !is TimerState.Inspecting) return
        val holdStart = inspectionHoldStart
        val progress = holdStart?.let {
            // No start delay: ready as soon as the finger is down.
            if (inspectionHoldDuration <= 0L) 1f
            else ((nowMillis - it).toFloat() / inspectionHoldDuration).coerceIn(0f, 1f)
        }
        _timerState.value = TimerState.Inspecting((nowMillis - inspectionStartTime).coerceAtLeast(0L), progress)
    }

    private fun onInspectionRelease(releaseUptimeMillis: Long) {
        if (inspectionStartPressDown) {
            inspectionStartPressDown = false
            return
        }
        val holdStart = inspectionHoldStart
        if (holdStart != null) {
            inspectionHoldStart = null
            // Judged from the timestamps, not the last published progress, which lags by a tick.
            if (releaseUptimeMillis - holdStart >= inspectionHoldDuration) {
                startSolveFromInspection(releaseUptimeMillis)
            } else {
                publishInspection(releaseUptimeMillis)
            }
        }
    }

    private fun startSolveFromInspection(startUptimeMillis: Long) {
        val penalty = Inspection.penaltyFor(startUptimeMillis - inspectionStartTime)
        startTimer(startUptimeMillis, inspectionPenalty = penalty)
    }

    /** Abandons inspection and returns to Idle without saving anything. */
    fun cancelInspection() {
        if (_timerState.value is TimerState.Inspecting) resetTimer()
    }

    private fun clearInspection() {
        inspectionJob?.cancel()
        inspectionJob = null
        inspectionHoldStart = null
        inspectionStartPressDown = false
    }

    private fun startHoldTimer(pressStartUptimeMillis: Long) {
        holdJob?.cancel()
        val holdDuration = timerStartDelayMillis.value.toLong()
        if (holdDuration <= 0L) {
            // No start delay: set synchronously, so a release that follows at once still starts the solve.
            _timerState.value = TimerState.Ready
            return
        }
        holdJob = viewModelScope.launch {
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

    private fun startTimer(startUptimeMillis: Long, inspectionPenalty: Penalty = Penalty.NONE) {
        clearInspection()
        startTime = startUptimeMillis
        activeInspectionPenalty = inspectionPenalty
        activeSolveMode = _currentMode.value
        activeSolveScramble = _currentScramble.value
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
        _timerState.value = TimerState.Finished(elapsed, inspectionPenalty = activeInspectionPenalty)
    }

    fun saveSolveWithPenalty(penalty: Penalty) {
        val currentState = _timerState.value
        if (currentState is TimerState.Finished) {
            val nowMs = System.currentTimeMillis()
            // The scramble and mode were captured when the solve started. generateNewScramble()
            // below runs concurrently, and for fast puzzles it can finish before this coroutine
            // resumes from getOrCreateActiveSession — reading _currentScramble here would save
            // the next scramble. A refresh or mode change while the result is on screen is ignored
            // until this solve is saved or discarded, so those fields stay the ones that were solved.
            val currentModeValue = activeSolveMode ?: _currentMode.value
            val capturedScramble = activeSolveScramble ?: _currentScramble.value
            activeSolveMode = null
            activeSolveScramble = null
            val ownerId = authManager.currentOwnerId

            resetTimer()
            generateNewScramble()

            viewModelScope.launch {
                var pendingIds = emptyList<String>()
                try {
                    val activeSession = sessionManager.getOrCreateActiveSession(
                        ownerId = ownerId,
                        mode = currentModeValue,
                        solveTimestamp = nowMs
                    )

                    val newSolve = SolveTime(
                        timeInMillis = currentState.time,
                        penalty = Inspection.moreSevere(penalty, currentState.inspectionPenalty),
                        scramble = capturedScramble,
                        mode = currentModeValue,
                        timestamp = nowMs,
                        sessionId = activeSession.id,
                        timingDevice = currentState.timingDevice
                    )

                    val newAllSolves = (_allSolves.value.filter { it.id != newSolve.id } + newSolve).sortedBy { it.timestamp }
                    pendingIds = listOf(newSolve.id)
                    markPendingUpserts(ownerId, listOf(newSolve))
                    if (authManager.currentOwnerId == ownerId) {
                        publishSolves(newAllSolves)
                    }

                    repository.saveSolve(newSolve, ownerId = ownerId, sessionId = activeSession.id)
                    settlePending(pendingIds)

                    // Check for records using solves for the captured mode, not whatever mode happens
                    // to be selected once this coroutine resumes. The averages scan the whole history,
                    // so they run off the main thread.
                    val record = withContext(defaultDispatcher) {
                        val modeSolves = newAllSolves.filter { it.mode == currentModeValue }
                        findRecord(newSolve, previousSolves = modeSolves - newSolve, newSolves = modeSolves)
                    }
                    if (record != null) _recordCelebration.value = record
                } catch (e: CancellationException) {
                    settlePending(pendingIds)
                    throw e
                } catch (e: Exception) {
                    onWriteFailed(ownerId, pendingIds, "Couldn't save the solve", e)
                }
            }
        }
    }

    fun discardSolve() {
        _recordCelebration.value = null
        activeSolveMode = null
        activeSolveScramble = null
        resetTimer()
    }

    fun generateNewScramble() {
        // Idle only: refreshing while a result is on screen would replace the scramble about to be saved.
        if (_timerState.value !is TimerState.Idle) return
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
        clearInspection()
        activeInspectionPenalty = Penalty.NONE
        _timerState.value = TimerState.Idle
    }

    fun deleteSolve(solve: SolveTime) {
        val ownerId = authManager.currentOwnerId
        markPendingDeletes(ownerId, listOf(solve.id))
        publishSolves(_allSolves.value.filter { it.id != solve.id })
        launchWrite(ownerId, listOf(solve.id), "Couldn't delete the solve") {
            repository.deleteSolve(solve, ownerId = ownerId)
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
        launchWrite(ownerId, listOf(solve.id), "Couldn't update the solve") {
            repository.updateSolvePenalty(solve, penalty, ownerId = ownerId)
        }
    }

    fun setStatsFilter(filter: StatsFilter) {
        _statsFilter.value = filter
    }

    fun clearAllSolves() {
        val ownerId = authManager.currentOwnerId
        val clearedIds = _allSolves.value.map { it.id }
        markPendingDeletes(ownerId, clearedIds)
        publishSolves(emptyList())
        launchWrite(ownerId, clearedIds, "Couldn't delete the solves") {
            repository.clearAllSolves(ownerId = ownerId)
        }
    }

    fun restoreSolves(previous: List<SolveTime>) {
        val ownerId = authManager.currentOwnerId
        val toRestoreIds = previous.map { it.id }.toSet()
        markPendingUpserts(ownerId, previous)
        publishSolves((_allSolves.value.filter { it.id !in toRestoreIds } + previous).sortedBy { it.timestamp })
        launchWrite(ownerId, toRestoreIds, "Couldn't restore the solves") {
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

    fun setInspectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setInspectionEnabled(enabled)
        }
    }

    fun setTimingDevice(device: TimingDevice) {
        viewModelScope.launch {
            settingsRepository.setTimingDevice(device)
        }
        if (device != TimingDevice.EXTERNAL_TIMER) {
            bluetoothTimer?.disconnect()
            // Drop a half-started Bluetooth solve; a finished one stays so it can still be saved.
            if (_timerState.value !is TimerState.Finished) resetTimer()
        } else if (_timerState.value is TimerState.Inspecting) {
            // The Bluetooth timer runs its own inspection.
            resetTimer()
        }
    }

    fun hasBluetoothPermissions(): Boolean = bluetoothTimer?.hasPermissions() == true

    fun isBluetoothEnabled(): Boolean = bluetoothTimer?.isBluetoothEnabled() == true

    fun startBluetoothScan() {
        bluetoothTimer?.startScan()
    }

    fun stopBluetoothScan() {
        bluetoothTimer?.stopScan()
    }

    fun connectBluetoothTimer(address: String) {
        bluetoothTimer?.connect(address)
    }

    fun disconnectBluetoothTimer() {
        bluetoothTimer?.disconnect()
    }

    fun clearBluetoothError() {
        bluetoothTimer?.clearError()
    }

    /**
     * Maps Bluetooth timer events onto the same state machine touch timing uses. The timer's own
     * measurement is authoritative: [SmartTimerEvent.Stopped] finishes with its time, not ours.
     */
    fun onSmartTimerEvent(event: SmartTimerEvent) {
        val state = _timerState.value
        when (event) {
            SmartTimerEvent.HandsOn -> if (state is TimerState.Idle) {
                _timerState.value = TimerState.Holding(0f)
            }
            SmartTimerEvent.GetSet -> if (state is TimerState.Idle || state is TimerState.Holding) {
                holdJob?.cancel()
                _timerState.value = TimerState.Ready
            }
            SmartTimerEvent.HandsOff, SmartTimerEvent.Idle, SmartTimerEvent.Inspection -> {
                if (state is TimerState.Holding || state is TimerState.Ready) {
                    _timerState.value = TimerState.Idle
                }
            }
            SmartTimerEvent.Running -> when (state) {
                is TimerState.Running -> Unit
                is TimerState.Finished -> {
                    // The next solve started on the timer before the last one was saved: keep it
                    // (no penalty) rather than silently dropping it.
                    saveSolveWithPenalty(Penalty.NONE)
                    startTimer(timeSource())
                }
                else -> {
                    holdJob?.cancel()
                    startTimer(timeSource())
                }
            }
            is SmartTimerEvent.Stopped -> if (state is TimerState.Running) {
                timerJob?.cancel()
                _timerState.value = TimerState.Finished(event.timeMs, TimingDevice.EXTERNAL_TIMER)
            }
            SmartTimerEvent.Disconnected -> if (state is TimerState.Holding || state is TimerState.Ready || state is TimerState.Running) {
                resetTimer()
            }
        }
    }

    fun setMode(mode: Mode) {
        // The mode a solve is filed under is the one it started in. Switching while the timer
        // is running or a result is waiting to be saved would file it under the new puzzle.
        if (_timerState.value !is TimerState.Idle) return
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

    fun clearWriteError() {
        _writeError.value = null
    }

    private fun findRecord(
        newSolve: SolveTime,
        previousSolves: List<SolveTime>,
        newSolves: List<SolveTime>
    ): RecordCelebration? {
        if (newSolve.penalty == Penalty.DNF) return null

        // Check for best single
        val previousBest = previousSolves
            .filter { it.penalty != Penalty.DNF }
            .minOfOrNull { it.displayTime }

        if (previousBest == null || newSolve.displayTime < previousBest) {
            return RecordCelebration(
                type = RecordType.BEST_SINGLE,
                time = newSolve.displayTime
            )
        }

        // Check for best Ao5
        if (newSolves.size >= 5) {
            val currentAo5 = AverageCalculator.averageOfN(newSolves, 5)
            val previousBestAo5 = AverageCalculator.bestAverageOfN(previousSolves, 5)

            if (currentAo5 != null && (previousBestAo5 == null || currentAo5 < previousBestAo5)) {
                return RecordCelebration(
                    type = RecordType.BEST_AO5,
                    time = currentAo5
                )
            }
        }

        // Check for best Ao12
        if (newSolves.size >= 12) {
            val currentAo12 = AverageCalculator.averageOfN(newSolves, 12)
            val previousBestAo12 = AverageCalculator.bestAverageOfN(previousSolves, 12)

            if (currentAo12 != null && (previousBestAo12 == null || currentAo12 < previousBestAo12)) {
                return RecordCelebration(
                    type = RecordType.BEST_AO12,
                    time = currentAo12
                )
            }
        }

        return null
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
        inspectionJob?.cancel()
        timerJob?.cancel()
        holdJob?.cancel()
        scrambleJob?.cancel()
    }

    private companion object {
        const val TAG = "TimerViewModel"
    }
}
