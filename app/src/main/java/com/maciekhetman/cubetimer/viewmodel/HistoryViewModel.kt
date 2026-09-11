package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.CubeTimerApplication
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthManagerImpl
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepository
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.domain.HistoricalPbCalculator
import com.maciekhetman.cubetimer.domain.HistoricalPbResult
import com.maciekhetman.cubetimer.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI State representing the complete presentation state of HistoryScreen.
 */
data class HistoryUiState(
    val solves: List<SolveTime> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
    val activeSessionCount: Int = 0,
    val allSolvesCount: Int = 0,
    val currentMode: Mode = Mode.CUBE_3x3,
    val currentFilter: StatsFilter = StatsFilter.ActiveSession,
    val activeSession: Session? = null,
    val sessions: List<Session> = emptyList(),
    val errorMessage: String? = null,
    val selectedSolve: SolveDetailState? = null
) {
    val isInitialLoading: Boolean get() = isLoading
    val activeSessionSolvesCount: Int get() = activeSessionCount
}

/**
 * One-shot UI side effects.
 */
sealed interface HistoryUiEffect {
    data class ShowUndoSnackbar(
        val message: String,
        val solve: SolveTime,
        val originalIndex: Int
    ) : HistoryUiEffect

    data class ShowMessage(val message: String) : HistoryUiEffect
}

/**
 * State for the interactive solve detail modal card.
 */
data class SolveDetailState(
    val solve: SolveTime,
    val solveNumber: Int,
    val priorBestTime: Long?,
    val isPb: Boolean,
    val pbDelta: Long?,
    val formattedPbDelta: String? = null,
    val pbResult: HistoricalPbResult? = null
)

private data class FilterScope(
    val mode: Mode,
    val filter: StatsFilter,
    val activeSessionId: String?,
    val ownerId: String
)

private data class Chunk1(
    val solves: List<SolveTime>,
    val isLoading: Boolean,
    val isLoadingMore: Boolean,
    val hasMore: Boolean,
    val totalCount: Int
)

private data class Chunk2(
    val activeSessionCount: Int,
    val allSolvesCount: Int,
    val currentMode: Mode,
    val currentFilter: StatsFilter,
    val errorMessage: String?
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    application: Application,
    private val solvesRepository: SolvesRepository,
    private val sessionManager: SessionManager,
    private val sessionRepository: SessionRepository,
    private val authManager: AuthManager,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    companion object {
        const val PAGE_SIZE = 50
    }

    constructor(
        application: Application,
        repository: SolvesRepository,
        sessionManager: SessionManager,
        sessionRepository: SessionRepository,
        authManager: AuthManager
    ) : this(
        application = application,
        solvesRepository = repository,
        sessionManager = sessionManager,
        sessionRepository = sessionRepository,
        authManager = authManager,
        defaultDispatcher = Dispatchers.Default
    )

    constructor(
        application: Application,
        repository: SolvesRepository,
        sessionManager: SessionManager,
        authManager: AuthManager
    ) : this(
        application = application,
        solvesRepository = repository,
        sessionManager = sessionManager,
        sessionRepository = (application as? CubeTimerApplication)?.sessionRepository
            ?: SessionRepositoryImpl(CubeDatabase.getInstance(application)),
        authManager = authManager
    )

    constructor(application: Application) : this(
        application = application,
        solvesRepository = (application as? CubeTimerApplication)?.solvesRepository
            ?: SolvesRepository(application),
        sessionManager = (application as? CubeTimerApplication)?.sessionManager
            ?: SessionManagerImpl(
                context = application,
                sessionRepository = SessionRepositoryImpl(CubeDatabase.getInstance(application)),
                solveDao = CubeDatabase.getInstance(application).solveDao(),
                authManager = AuthManagerImpl.getInstance(application)
            ),
        sessionRepository = (application as? CubeTimerApplication)?.sessionRepository
            ?: SessionRepositoryImpl(CubeDatabase.getInstance(application)),
        authManager = (application as? CubeTimerApplication)?.authManager
            ?: AuthManagerImpl.getInstance(application)
    )

    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    private val _currentFilter = MutableStateFlow<StatsFilter>(StatsFilter.ActiveSession)
    val currentFilter: StateFlow<StatsFilter> = _currentFilter.asStateFlow()

    private val _solves = MutableStateFlow<List<SolveTime>>(emptyList())
    val solves: StateFlow<List<SolveTime>> = _solves.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _activeSessionCount = MutableStateFlow(0)
    val activeSessionSolvesCount: StateFlow<Int> = _activeSessionCount.asStateFlow()

    private val _allSolvesCount = MutableStateFlow(0)
    val allSolvesCount: StateFlow<Int> = _allSolvesCount.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _effectsChannel = Channel<HistoryUiEffect>(Channel.BUFFERED)
    val effects: Flow<HistoryUiEffect> = _effectsChannel.receiveAsFlow()

    private val _selectedSolveDetail = MutableStateFlow<SolveDetailState?>(null)
    val selectedSolveDetail: StateFlow<SolveDetailState?> = _selectedSolveDetail.asStateFlow()
    val selectedSolve: StateFlow<SolveDetailState?> = _selectedSolveDetail.asStateFlow()

    private val currentOwnerId: String get() = authManager.currentOwnerId

    // Undo cache
    private var lastDeletedSolve: SolveTime? = null
    private var lastDeletedIndex: Int? = null
    private var lastClearedSolves: List<SolveTime>? = null

    // Pagination & observer jobs
    private var paginationJob: Job? = null
    private var countObservationJob: Job? = null
    private var currentOffset: Int = 0

    val activeSession: StateFlow<Session?> = combine(
        _currentMode,
        authManager.authState
    ) { mode: Mode, authState: AuthState ->
        val ownerId = when (authState) {
            is AuthState.Authenticated -> authState.user.id
            is AuthState.Admin -> authState.user.id
            else -> "guest"
        }
        Pair(ownerId, mode)
    }.flatMapLatest { pair ->
        val (ownerId, mode) = pair
        sessionManager.getActiveSessionFlow(ownerId, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessions: StateFlow<List<Session>> = combine(
        _currentMode,
        authManager.authState
    ) { mode: Mode, authState: AuthState ->
        val ownerId = when (authState) {
            is AuthState.Authenticated -> authState.user.id
            is AuthState.Admin -> authState.user.id
            else -> "guest"
        }
        Pair(ownerId, mode)
    }.flatMapLatest { pair ->
        val (ownerId, mode) = pair
        sessionRepository.observeActiveSessions(ownerId, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<HistoryUiState> = combine(
        combine(_solves, _isLoading, _isLoadingMore, _hasMore, _totalCount) { s, l, lm, hm, tc ->
            Chunk1(s, l, lm, hm, tc)
        },
        combine(_activeSessionCount, _allSolvesCount, _currentMode, _currentFilter, _errorMessage) { ac, alc, cm, cf, em ->
            Chunk2(ac, alc, cm, cf, em)
        },
        combine(activeSession, sessions, _selectedSolveDetail) { asess, sessList, selSolve ->
            Triple(asess, sessList, selSolve)
        }
    ) { c1, c2, (asess, sessList, selSolve) ->
        HistoryUiState(
            solves = c1.solves,
            isLoading = c1.isLoading,
            isLoadingMore = c1.isLoadingMore,
            hasMore = c1.hasMore,
            totalCount = c1.totalCount,
            activeSessionCount = c2.activeSessionCount,
            allSolvesCount = c2.allSolvesCount,
            currentMode = c2.currentMode,
            currentFilter = c2.currentFilter,
            activeSession = asess,
            sessions = sessList,
            errorMessage = c2.errorMessage,
            selectedSolve = selSolve
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    init {
        viewModelScope.launch {
            combine(
                _currentMode,
                _currentFilter,
                activeSession,
                authManager.authState
            ) { mode, filter, activeSes, authState ->
                val ownerId = when (authState) {
                    is AuthState.Authenticated -> authState.user.id
                    is AuthState.Admin -> authState.user.id
                    else -> "guest"
                }
                FilterScope(
                    mode = mode,
                    filter = filter,
                    activeSessionId = activeSes?.id,
                    ownerId = ownerId
                )
            }.distinctUntilChanged()
                .collectLatest { scope ->
                    observeCounts(scope)
                    reloadFirstPage(scope)
                }
        }
    }

    fun setMode(mode: Mode) {
        if (_currentMode.value != mode) {
            _currentMode.value = mode
        }
    }

    fun setFilter(filter: StatsFilter) {
        if (_currentFilter.value != filter) {
            _currentFilter.value = filter
        }
    }

    fun refresh() {
        val scope = FilterScope(
            mode = _currentMode.value,
            filter = _currentFilter.value,
            activeSessionId = activeSession.value?.id,
            ownerId = currentOwnerId
        )
        viewModelScope.launch {
            reloadFirstPage(scope)
        }
    }

    private suspend fun reloadFirstPage(scope: FilterScope) {
        paginationJob?.cancel()
        _isLoading.value = true
        currentOffset = 0
        try {
            val initialBatch = fetchPage(scope, limit = PAGE_SIZE, offset = 0)
            _solves.value = initialBatch
            currentOffset = initialBatch.size
            _hasMore.value = initialBatch.size >= PAGE_SIZE && (_totalCount.value == 0 || _solves.value.size < _totalCount.value)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to load solves: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    fun loadMore() {
        if (_isLoading.value || _isLoadingMore.value || !_hasMore.value) {
            return
        }

        val currentScope = FilterScope(
            mode = _currentMode.value,
            filter = _currentFilter.value,
            activeSessionId = activeSession.value?.id,
            ownerId = currentOwnerId
        )

        _isLoadingMore.value = true
        paginationJob = viewModelScope.launch {
            try {
                val nextBatch = fetchPage(currentScope, limit = PAGE_SIZE, offset = currentOffset)
                if (nextBatch.isNotEmpty()) {
                    val currentIds = _solves.value.map { it.id }.toSet()
                    val newUnique = nextBatch.filter { it.id !in currentIds }
                    _solves.value = _solves.value + newUnique
                    currentOffset += nextBatch.size
                    _hasMore.value = nextBatch.size >= PAGE_SIZE && (_totalCount.value == 0 || _solves.value.size < _totalCount.value)
                } else {
                    _hasMore.value = false
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load more solves: ${e.message}"
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadNextPage() = loadMore()

    private suspend fun fetchPage(scope: FilterScope, limit: Int, offset: Int): List<SolveTime> {
        return when (val filter = scope.filter) {
            is StatsFilter.ActiveSession -> {
                val sId = scope.activeSessionId
                if (sId != null) {
                    solvesRepository.getSolvesPagedBySession(
                        sessionId = sId,
                        ownerId = scope.ownerId,
                        limit = limit,
                        offset = offset
                    )
                } else {
                    solvesRepository.getSolvesPagedByEvent(
                        mode = scope.mode,
                        ownerId = scope.ownerId,
                        limit = limit,
                        offset = offset
                    )
                }
            }
            is StatsFilter.AllSessions -> {
                solvesRepository.getSolvesPagedByEvent(
                    mode = scope.mode,
                    ownerId = scope.ownerId,
                    limit = limit,
                    offset = offset
                )
            }
            is StatsFilter.SpecificSession -> {
                solvesRepository.getSolvesPagedBySession(
                    sessionId = filter.sessionId,
                    ownerId = scope.ownerId,
                    limit = limit,
                    offset = offset
                )
            }
        }
    }

    private fun observeCounts(scope: FilterScope) {
        countObservationJob?.cancel()
        countObservationJob = viewModelScope.launch {
            launch {
                solvesRepository.observeSolveCountByEvent(scope.mode, scope.ownerId)
                    .collect { count ->
                        _allSolvesCount.value = count
                        if (scope.filter is StatsFilter.AllSessions || (scope.filter is StatsFilter.ActiveSession && scope.activeSessionId == null)) {
                            _totalCount.value = count
                            _hasMore.value = _solves.value.size < count
                        }
                    }
            }

            val activeId = scope.activeSessionId
            if (activeId != null) {
                launch {
                    solvesRepository.observeSolveCountBySession(activeId, scope.ownerId)
                        .collect { count ->
                            _activeSessionCount.value = count
                            if (scope.filter is StatsFilter.ActiveSession) {
                                _totalCount.value = count
                                _hasMore.value = _solves.value.size < count
                            }
                        }
                }
            } else {
                _activeSessionCount.value = 0
            }

            if (scope.filter is StatsFilter.SpecificSession) {
                launch {
                    solvesRepository.observeSolveCountBySession(scope.filter.sessionId, scope.ownerId)
                        .collect { count ->
                            _totalCount.value = count
                            _hasMore.value = _solves.value.size < count
                        }
                }
            }
        }
    }

    fun updateSolvePenalty(solve: SolveTime, penalty: Penalty) {
        val previousPenalty = solve.penalty
        if (previousPenalty == penalty) return

        val updatedSolve = solve.copy(penalty = penalty)
        _solves.value = _solves.value.map { existing ->
            if (existing.id == solve.id) updatedSolve else existing
        }

        if (_selectedSolveDetail.value?.solve?.id == solve.id) {
            val currentDetail = _selectedSolveDetail.value!!
            val newPbResult = HistoricalPbCalculator.calculate(updatedSolve, currentDetail.priorBestTime)
            _selectedSolveDetail.value = currentDetail.copy(
                solve = updatedSolve,
                isPb = newPbResult.isPb,
                pbDelta = newPbResult.deltaMs,
                formattedPbDelta = newPbResult.formattedDelta,
                pbResult = newPbResult
            )
        }

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.updateSolvePenalty(solve, penalty, ownerId = ownerId)
            } catch (e: Exception) {
                _solves.value = _solves.value.map { existing ->
                    if (existing.id == solve.id) existing.copy(penalty = previousPenalty) else existing
                }
                if (_selectedSolveDetail.value?.solve?.id == solve.id) {
                    val currentDetail = _selectedSolveDetail.value!!
                    val revertedSolve = solve.copy(penalty = previousPenalty)
                    val revertedPbResult = HistoricalPbCalculator.calculate(revertedSolve, currentDetail.priorBestTime)
                    _selectedSolveDetail.value = currentDetail.copy(
                        solve = revertedSolve,
                        isPb = revertedPbResult.isPb,
                        pbDelta = revertedPbResult.deltaMs,
                        formattedPbDelta = revertedPbResult.formattedDelta,
                        pbResult = revertedPbResult
                    )
                }
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to update penalty: ${e.message}"))
            }
        }
    }

    fun deleteSolve(solve: SolveTime) {
        val index = _solves.value.indexOfFirst { it.id == solve.id }
        if (index == -1) return

        if (_selectedSolveDetail.value?.solve?.id == solve.id) {
            _selectedSolveDetail.value = null
        }

        lastDeletedSolve = solve
        lastDeletedIndex = index

        _solves.value = _solves.value.filter { it.id != solve.id }
        _totalCount.value = (_totalCount.value - 1).coerceAtLeast(0)
        currentOffset = (currentOffset - 1).coerceAtLeast(0)

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.deleteSolve(solve, ownerId = ownerId)
                _effectsChannel.send(
                    HistoryUiEffect.ShowUndoSnackbar(
                        message = "Solve deleted",
                        solve = solve,
                        originalIndex = index
                    )
                )
            } catch (e: Exception) {
                val mutable = _solves.value.toMutableList()
                mutable.add(index.coerceIn(0, mutable.size), solve)
                _solves.value = mutable
                _totalCount.value += 1
                currentOffset += 1
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to delete solve: ${e.message}"))
            }
        }
    }

    fun restoreSolve(solve: SolveTime) {
        val mutable = _solves.value.toMutableList()
        val originalIndex = if (lastDeletedSolve?.id == solve.id) (lastDeletedIndex ?: 0) else 0
        val insertIndex = originalIndex.coerceIn(0, mutable.size)
        if (mutable.none { it.id == solve.id }) {
            mutable.add(insertIndex, solve)
            _solves.value = mutable
            _totalCount.value += 1
            currentOffset += 1
        }
        lastDeletedSolve = null
        lastDeletedIndex = null

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(listOf(solve), ownerId = ownerId)
            } catch (e: Exception) {
                _solves.value = _solves.value.filter { it.id != solve.id }
                _totalCount.value = (_totalCount.value - 1).coerceAtLeast(0)
                currentOffset = (currentOffset - 1).coerceAtLeast(0)
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to restore solve: ${e.message}"))
            }
        }
    }

    fun undoDelete() {
        val solveToRestore = lastDeletedSolve ?: return
        restoreSolve(solveToRestore)
    }

    fun clearHistory() {
        val solvesToClear = _solves.value
        if (solvesToClear.isEmpty()) return

        lastClearedSolves = solvesToClear
        _solves.value = emptyList()
        _totalCount.value = 0
        _hasMore.value = false
        currentOffset = 0

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                val filter = _currentFilter.value
                when (filter) {
                    is StatsFilter.AllSessions -> {
                        solvesRepository.clearSolvesByEvent(_currentMode.value, ownerId)
                    }
                    is StatsFilter.ActiveSession -> {
                        val activeId = activeSession.value?.id
                        if (activeId != null) {
                            solvesRepository.clearSolvesBySession(activeId, ownerId)
                        } else {
                            solvesRepository.clearSolvesByEvent(_currentMode.value, ownerId)
                        }
                    }
                    is StatsFilter.SpecificSession -> {
                        solvesRepository.clearSolvesBySession(filter.sessionId, ownerId)
                    }
                }
                _effectsChannel.send(HistoryUiEffect.ShowMessage("History cleared"))
            } catch (e: Exception) {
                _solves.value = solvesToClear
                _totalCount.value = solvesToClear.size
                _hasMore.value = solvesToClear.size >= PAGE_SIZE
                currentOffset = solvesToClear.size
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to clear history: ${e.message}"))
            }
        }
    }

    fun clearCurrentFilterHistory() = clearHistory()

    fun undoClearHistory() {
        val cleared = lastClearedSolves ?: return
        lastClearedSolves = null

        _solves.value = cleared
        _totalCount.value = cleared.size
        currentOffset = cleared.size
        _hasMore.value = cleared.size >= PAGE_SIZE

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(cleared, ownerId = ownerId)
            } catch (e: Exception) {
                _solves.value = emptyList()
                _totalCount.value = 0
                currentOffset = 0
                _hasMore.value = false
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to restore history: ${e.message}"))
            }
        }
    }

    fun selectSolveForDetail(solve: SolveTime) {
        viewModelScope.launch {
            val ownerId = currentOwnerId
            val solvedAtIso = CubeTypeConverters.epochMillisToIso(solve.timestamp)
            val priorBestTime = solvesRepository.getPriorBestSolveDuration(
                mode = solve.mode,
                solvedAtIso = solvedAtIso,
                ownerId = ownerId,
                excludeSolveId = solve.id
            )

            val solveIndex = _solves.value.indexOfFirst { it.id == solve.id }
            val solveNumber = if (solveIndex != -1) {
                (_totalCount.value - solveIndex).coerceAtLeast(1)
            } else {
                1
            }

            val pbResult = HistoricalPbCalculator.calculate(
                solve = solve,
                priorBestDurationMs = priorBestTime
            )

            _selectedSolveDetail.value = SolveDetailState(
                solve = solve,
                solveNumber = solveNumber,
                priorBestTime = priorBestTime,
                isPb = pbResult.isPb,
                pbDelta = pbResult.deltaMs,
                formattedPbDelta = pbResult.formattedDelta,
                pbResult = pbResult
            )
        }
    }

    fun dismissSolveDetail() {
        _selectedSolveDetail.value = null
    }
}
