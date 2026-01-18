package com.maciekhetman.cubetimer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Penalty {
    NONE,
    PLUS_TWO,
    DNF
}

enum class Mode {
    CUBE_3x3,
    CUBE_2x2;
    
    val displayName: String
        get() = when (this) {
            CUBE_3x3 -> "3x3"
            CUBE_2x2 -> "2x2"
        }
}

data class SolveTime(
    val timeInMillis: Long,
    val penalty: Penalty = Penalty.NONE,
    val timestamp: Long = System.currentTimeMillis(),
    val scramble: String = "",
    val mode: Mode = Mode.CUBE_3x3
) {
    val displayTime: Long
        get() = when (penalty) {
            Penalty.NONE -> timeInMillis
            Penalty.PLUS_TWO -> timeInMillis + 2000
            Penalty.DNF -> timeInMillis
        }
}

sealed class TimerState {
    object Idle : TimerState()
    data class Holding(val progress: Float) : TimerState()
    object Ready : TimerState()
    data class Running(val elapsedTime: Long) : TimerState()
    data class Finished(val time: Long) : TimerState()
}

enum class RecordType {
    BEST_SINGLE,
    BEST_AO5,
    BEST_AO12
}

data class RecordCelebration(
    val type: RecordType,
    val time: Long
)

class TimerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SolvesRepository(application)
    
    private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    private val _allSolves = MutableStateFlow<List<SolveTime>>(emptyList())
    
    private val _solves = MutableStateFlow<List<SolveTime>>(emptyList())
    val solves: StateFlow<List<SolveTime>> = _solves.asStateFlow()
    
    private val _currentScramble = MutableStateFlow(ScrambleGenerator.generateScramble(Mode.CUBE_3x3))
    val currentScramble: StateFlow<String> = _currentScramble.asStateFlow()
    
    private val _recordCelebration = MutableStateFlow<RecordCelebration?>(null)
    val recordCelebration: StateFlow<RecordCelebration?> = _recordCelebration.asStateFlow()

    private var timerJob: Job? = null
    private var holdJob: Job? = null
    private var startTime: Long = 0

    init {
        // Load saved solves on initialization
        viewModelScope.launch {
            repository.solvesFlow.collect { savedSolves ->
                _allSolves.value = savedSolves
                _solves.value = savedSolves.filter { it.mode == _currentMode.value }
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
            val holdDuration = 500L
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
    
    fun setMode(mode: Mode) {
        _currentMode.value = mode
        _solves.value = _allSolves.value.filter { it.mode == mode }
        generateNewScramble()
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
            val currentAo5 = calculateAverageOfN(newSolves, 5)
            val previousBestAo5 = findBestAverageOfN(previousSolves, 5)
            
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
            val currentAo12 = calculateAverageOfN(newSolves, 12)
            val previousBestAo12 = findBestAverageOfN(previousSolves, 12)
            
            if (currentAo12 != null && (previousBestAo12 == null || currentAo12 < previousBestAo12)) {
                _recordCelebration.value = RecordCelebration(
                    type = RecordType.BEST_AO12,
                    time = currentAo12
                )
            }
        }
    }
    
    private fun calculateAverageOfN(solves: List<SolveTime>, n: Int): Long? {
        if (solves.size < n) return null
        val lastN = solves.takeLast(n)
        val validSolves = lastN.filter { it.penalty != Penalty.DNF }
        
        if (validSolves.size < (n * 0.6).toInt()) return null
        
        return validSolves.map { it.displayTime }.average().toLong()
    }
    
    private fun findBestAverageOfN(solves: List<SolveTime>, n: Int): Long? {
        if (solves.size < n) return null
        
        var bestAverage: Long? = null
        
        for (i in 0..(solves.size - n)) {
            val subList = solves.subList(i, i + n)
            val validSolves = subList.filter { it.penalty != Penalty.DNF }
            
            if (validSolves.size >= (n * 0.6).toInt()) {
                val average = validSolves.map { it.displayTime }.average().toLong()
                if (bestAverage == null || average < bestAverage) {
                    bestAverage = average
                }
            }
        }
        
        return bestAverage
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        holdJob?.cancel()
    }
}
