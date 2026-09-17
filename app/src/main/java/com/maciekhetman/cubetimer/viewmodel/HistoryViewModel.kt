package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.CubeTimerApplication
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthManagerImpl
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.dto.SessionWithStats
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.*
import com.maciekhetman.cubetimer.data.session.DeletedSessionSnapshot
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepository
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.domain.HistoricalPbCalculator
import com.maciekhetman.cubetimer.domain.HistoricalPbResult
import com.maciekhetman.cubetimer.domain.csv.CsvExporter
import com.maciekhetman.cubetimer.domain.csv.CsvImportStatus
import com.maciekhetman.cubetimer.domain.csv.CsvImporter
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

private data class SessionData(
    val sessionsWithStats: List<SessionWithStats>,
    val expandedIds: Set<String>,
    val cache: Map<String, List<SolveTime>>,
    val loadingIds: Set<String>
)

private data class FilterSettings(
    val sessionSort: SessionSortOrder,
    val solveSort: SolveSortOrder,
    val penaltyFilter: PenaltyFilter,
    val timeRangeFilter: TimeRangeFilter,
    val dateRangeFilter: DateRangeFilter
)

private data class GroupStateChunk(
    val sessionGroups: List<SessionGroupUiModel>,
    val expandedSessionIds: Set<String>,
    val selectedSolveIds: Set<String>
)

private data class SolveFilterQuad(
    val solveSort: SolveSortOrder,
    val penaltyFilter: PenaltyFilter,
    val timeRangeFilter: TimeRangeFilter,
    val dateRangeFilter: DateRangeFilter
)

private data class FilterStateChunk(
    val sessionSort: SessionSortOrder,
    val puzzleScope: PuzzleScope,
    val sessionKindFilter: SessionKindFilter,
    val solveSort: SolveSortOrder,
    val penaltyFilter: PenaltyFilter,
    val timeRangeFilter: TimeRangeFilter,
    val dateRangeFilter: DateRangeFilter,
    val isFilterSheetOpen: Boolean,
    val activeFilterSheetTab: Int
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
    database: CubeDatabase? = null,
    sessionDao: SessionDao? = null,
    solveDao: SolveDao? = null,
    syncOutboxDao: SyncOutboxDao? = null,
    private val csvExporter: CsvExporter = CsvExporter,
    csvImporter: CsvImporter? = null,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    private val effectiveDatabase: CubeDatabase = database
        ?: (application as? CubeTimerApplication)?.database
        ?: CubeDatabase.getInstance(application)

    private val effectiveSessionDao: SessionDao = sessionDao
        ?: effectiveDatabase.sessionDao()

    private val effectiveSolveDao: SolveDao = solveDao
        ?: effectiveDatabase.solveDao()

    private val effectiveSyncOutboxDao: SyncOutboxDao = syncOutboxDao
        ?: effectiveDatabase.syncOutboxDao()

    private val effectiveCsvImporter: CsvImporter = csvImporter
        ?: CsvImporter(
            database = effectiveDatabase,
            solveDao = effectiveSolveDao,
            sessionDao = effectiveSessionDao,
            syncOutboxDao = effectiveSyncOutboxDao,
            ioDispatcher = ioDispatcher
        )

    // --- Session Tab Filter/Sort State ---
    private val _sessionSort = MutableStateFlow(SessionSortOrder.MOST_RECENT)
    val sessionSort: StateFlow<SessionSortOrder> = _sessionSort.asStateFlow()

    private val _puzzleScope = MutableStateFlow(PuzzleScope.ACTIVE_PUZZLE)
    val puzzleScope: StateFlow<PuzzleScope> = _puzzleScope.asStateFlow()

    private val _sessionKindFilter = MutableStateFlow(SessionKindFilter.ALL)
    val sessionKindFilter: StateFlow<SessionKindFilter> = _sessionKindFilter.asStateFlow()

    private val _expandedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedSessionIds: StateFlow<Set<String>> = _expandedSessionIds.asStateFlow()

    // --- Solve Tab Filter/Sort State ---
    private val _solveSort = MutableStateFlow(SolveSortOrder.MOST_RECENT)
    val solveSort: StateFlow<SolveSortOrder> = _solveSort.asStateFlow()

    private val _penaltyFilter = MutableStateFlow(PenaltyFilter.ALL)
    val penaltyFilter: StateFlow<PenaltyFilter> = _penaltyFilter.asStateFlow()

    private val _timeRangeFilter = MutableStateFlow(TimeRangeFilter())
    val timeRangeFilter: StateFlow<TimeRangeFilter> = _timeRangeFilter.asStateFlow()

    private val _dateRangeFilter = MutableStateFlow(DateRangeFilter())
    val dateRangeFilter: StateFlow<DateRangeFilter> = _dateRangeFilter.asStateFlow()

    // --- Filter Bottom Sheet State ---
    private val _isFilterSheetOpen = MutableStateFlow(false)
    val isFilterSheetOpen: StateFlow<Boolean> = _isFilterSheetOpen.asStateFlow()

    private val _activeFilterSheetTab = MutableStateFlow(0)
    val activeFilterSheetTab: StateFlow<Int> = _activeFilterSheetTab.asStateFlow()

    // --- Multi-Selection State ---
    private val _selectedSolveIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedSolveIds: StateFlow<Set<String>> = _selectedSolveIds.asStateFlow()

    // --- Session Solves Dynamic Loading & Cache ---
    private val _sessionSolvesCache = MutableStateFlow<Map<String, List<SolveTime>>>(emptyMap())
    private val _loadingSessionSolves = MutableStateFlow<Set<String>>(emptySet())
    private val expandedSessionJobs = ConcurrentHashMap<String, Job>()

    // --- Preserved Flat Solves & Legacy State ---
    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    private val _currentFilter = MutableStateFlow<StatsFilter>(StatsFilter.AllSessions)
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
    val uiEffect: Flow<HistoryUiEffect> get() = effects

    private val _selectedSolveDetail = MutableStateFlow<SolveDetailState?>(null)
    val selectedSolveDetail: StateFlow<SolveDetailState?> = _selectedSolveDetail.asStateFlow()
    val selectedSolve: StateFlow<SolveDetailState?> = _selectedSolveDetail.asStateFlow()

    private val currentOwnerId: String get() = authManager.currentOwnerId

    // Undo caches
    private var lastDeletedSolve: SolveTime? = null
    private var lastDeletedIndex: Int? = null
    private var lastBatchDeletedSolves: List<SolveTime>? = null
    private var lastDeletedSessionSnapshot: DeletedSessionSnapshot? = null
    private var lastClearedAllSolves: List<SolveTime>? = null

    // Pagination & observer jobs
    private var paginationJob: Job? = null
    private var countObservationJob: Job? = null
    private var currentOffset: Int = 0

    val activeSession: StateFlow<Session?> = combine(
        _currentMode,
        authManager.authState
    ) { mode: Mode, authState: AuthState ->
        Pair(authState.ownerId, mode)
    }.flatMapLatest { pair ->
        val (ownerId, mode) = pair
        sessionManager.getActiveSessionFlow(ownerId, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sessions: StateFlow<List<Session>> = combine(
        _currentMode,
        authManager.authState
    ) { mode: Mode, authState: AuthState ->
        Pair(authState.ownerId, mode)
    }.flatMapLatest { pair ->
        val (ownerId, mode) = pair
        sessionRepository.observeActiveSessions(ownerId, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Reactive Session Groups Flow ---
    private val rawSessionsWithStats: Flow<List<SessionWithStats>> = combine(
        authManager.authState,
        _currentMode,
        _puzzleScope,
        _sessionKindFilter
    ) { authState, mode, scope, kindFilter ->
        val ownerId = authState.ownerId
        val event = if (scope == PuzzleScope.ACTIVE_PUZZLE) CubeTypeConverters.fromMode(mode) else null
        val kind = when (kindFilter) {
            SessionKindFilter.ALL -> null
            SessionKindFilter.MANUAL_ONLY -> "manual"
            SessionKindFilter.AUTOMATIC_ONLY -> "automatic"
        }
        Triple(ownerId, event, kind)
    }.distinctUntilChanged().flatMapLatest { (ownerId, event, kind) ->
        effectiveSessionDao.observeSessionsWithStats(ownerId, event, kind)
    }

    private val sessionGroupsFlow: Flow<List<SessionGroupUiModel>> = combine(
        rawSessionsWithStats,
        _expandedSessionIds,
        _sessionSolvesCache,
        _loadingSessionSolves
    ) { sessionsWithStats, expandedIds, cache, loadingIds ->
        SessionData(sessionsWithStats, expandedIds, cache, loadingIds)
    }.combine(
        combine(
            _sessionSort,
            _solveSort,
            _penaltyFilter,
            _timeRangeFilter,
            _dateRangeFilter
        ) { sSort, slvSort, penFilter, timeFilter, dateFilter ->
            FilterSettings(sSort, slvSort, penFilter, timeFilter, dateFilter)
        }
    ) { sessionData, filterSettings ->
        buildSessionGroups(sessionData, filterSettings)
    }.flowOn(defaultDispatcher)

    // --- Unified UI State Flow ---
    val uiState: StateFlow<HistoryUiState> = combine(
        combine(sessionGroupsFlow, _expandedSessionIds, _selectedSolveIds) { groups, expanded, selected ->
            GroupStateChunk(groups, expanded, selected)
        },
        combine(
            combine(_sessionSort, _puzzleScope, _sessionKindFilter) { sSort, pScope, sKind ->
                Triple(sSort, pScope, sKind)
            },
            combine(_solveSort, _penaltyFilter, _timeRangeFilter, _dateRangeFilter) { slvSort, pFilter, tFilter, dFilter ->
                SolveFilterQuad(slvSort, pFilter, tFilter, dFilter)
            },
            combine(_isFilterSheetOpen, _activeFilterSheetTab) { open, tab ->
                Pair(open, tab)
            }
        ) { (sSort, pScope, sKind), (slvSort, pFilter, tFilter, dFilter), (open, tab) ->
            FilterStateChunk(
                sessionSort = sSort,
                puzzleScope = pScope,
                sessionKindFilter = sKind,
                solveSort = slvSort,
                penaltyFilter = pFilter,
                timeRangeFilter = tFilter,
                dateRangeFilter = dFilter,
                isFilterSheetOpen = open,
                activeFilterSheetTab = tab
            )
        },
        combine(
            combine(_solves, _isLoading, _isLoadingMore, _hasMore, _totalCount) { s, l, lm, hm, tc ->
                Chunk1(s, l, lm, hm, tc)
            },
            combine(_activeSessionCount, _allSolvesCount, _currentMode, _currentFilter, _errorMessage) { ac, alc, cm, cf, em ->
                Chunk2(ac, alc, cm, cf, em)
            },
            combine(activeSession, sessions, _selectedSolveDetail) { asess, sessList, selSolve ->
                Triple(asess, sessList, selSolve)
            }
        ) { c1, c2, c3 ->
            Triple(c1, c2, c3)
        }
    ) { groups: GroupStateChunk, filters: FilterStateChunk, legacy: Triple<Chunk1, Chunk2, Triple<Session?, List<Session>, SolveDetailState?>> ->
        val c1 = legacy.first
        val c2 = legacy.second
        val (asess, sessList, selSolve) = legacy.third
        HistoryUiState(
            sessionGroups = groups.sessionGroups,
            expandedSessionIds = groups.expandedSessionIds,
            selectedSolveIds = groups.selectedSolveIds,
            sessionSort = filters.sessionSort,
            puzzleScope = filters.puzzleScope,
            sessionKindFilter = filters.sessionKindFilter,
            solveSort = filters.solveSort,
            penaltyFilter = filters.penaltyFilter,
            timeRangeFilter = filters.timeRangeFilter,
            dateRangeFilter = filters.dateRangeFilter,
            isFilterSheetOpen = filters.isFilterSheetOpen,
            activeFilterSheetTab = filters.activeFilterSheetTab,
            // Legacy backwards-compatible fields
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
        observeExpandedSessions()
        viewModelScope.launch {
            combine(
                _currentMode,
                _currentFilter,
                activeSession,
                authManager.authState
            ) { mode, filter, activeSes, authState ->
                FilterScope(
                    mode = mode,
                    filter = filter,
                    activeSessionId = activeSes?.id,
                    ownerId = authState.ownerId
                )
            }.distinctUntilChanged()
                .collectLatest { scope ->
                    observeCounts(scope)
                    reloadFirstPage(scope)
                }
        }
    }

    private fun observeExpandedSessions() {
        viewModelScope.launch {
            var lastOwnerId: String? = null
            combine(_expandedSessionIds, authManager.authState) { expandedIds, authState ->
                Pair(expandedIds, authState.ownerId)
            }.collect { (expandedIds, ownerId) ->
                if (lastOwnerId != null && lastOwnerId != ownerId) {
                    expandedSessionJobs.values.forEach { it.cancel() }
                    expandedSessionJobs.clear()
                    _sessionSolvesCache.value = emptyMap()
                    _loadingSessionSolves.value = emptySet()
                    _expandedSessionIds.value = emptySet()
                    _selectedSolveIds.value = emptySet()
                }
                lastOwnerId = ownerId

                // Cancel jobs for sessions no longer expanded
                val toRemove = expandedSessionJobs.keys - expandedIds
                for (id in toRemove) {
                    expandedSessionJobs.remove(id)?.cancel()
                    _loadingSessionSolves.update { it - id }
                }

                // Start jobs for newly expanded sessions
                for (sessionId in expandedIds) {
                    if (!expandedSessionJobs.containsKey(sessionId)) {
                        val job = viewModelScope.launch {
                            _loadingSessionSolves.update { it + sessionId }
                            effectiveSolveDao.observeSolvesBySessionDesc(ownerId, sessionId)
                                .collect { entities: List<SolveEntity> ->
                                    val domainSolves = entities.map { it.toSolveTime() }
                                    _sessionSolvesCache.update { it + (sessionId to domainSolves) }
                                    _loadingSessionSolves.update { it - sessionId }
                                }
                        }
                        expandedSessionJobs[sessionId] = job
                    }
                }
            }
        }
    }

    private fun buildSessionGroups(
        data: SessionData,
        filters: FilterSettings
    ): List<SessionGroupUiModel> {
        val groups = data.sessionsWithStats.map { sws ->
            val domainSession = sws.session.toDomain()
            val isExpanded = data.expandedIds.contains(domainSession.id)
            val isSolvesLoading = data.loadingIds.contains(domainSession.id)
            val rawSolves = if (isExpanded) {
                data.cache[domainSession.id] ?: emptyList()
            } else {
                emptyList()
            }
            val childSolves = if (isExpanded && rawSolves.isNotEmpty()) {
                val filtered = rawSolves.filterSolves(
                    penaltyFilter = filters.penaltyFilter,
                    timeRangeFilter = filters.timeRangeFilter,
                    dateRangeFilter = filters.dateRangeFilter
                )
                filtered.sortSolves(filters.solveSort)
            } else {
                rawSolves
            }

            val solveNumbers = rawSolves
                .sortedBy { it.timestamp }
                .withIndex()
                .associate { (index, solve) -> solve.id to index + 1 }

            SessionGroupUiModel(
                session = domainSession,
                solveCount = sws.solveCount,
                bestDurationMs = sws.bestDurationMs,
                avgDurationMs = sws.avgDurationMs,
                isExpanded = isExpanded,
                solves = childSolves,
                isSolvesLoading = isSolvesLoading,
                solveNumbers = solveNumbers
            )
        }

        return groups.sortSessionGroups(filters.sessionSort)
    }

    private fun List<SessionGroupUiModel>.sortSessionGroups(order: SessionSortOrder): List<SessionGroupUiModel> {
        return when (order) {
            SessionSortOrder.MOST_RECENT -> sortedByDescending { it.session.startedAt }
            SessionSortOrder.OLDEST -> sortedBy { it.session.startedAt }
            SessionSortOrder.NAME_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.session.name })
            SessionSortOrder.NAME_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.session.name })
            SessionSortOrder.MOST_SOLVES -> sortedWith(
                compareByDescending<SessionGroupUiModel> { it.solveCount }
                    .thenByDescending { it.session.startedAt }
            )
        }
    }

    private fun List<SolveTime>.filterSolves(
        penaltyFilter: PenaltyFilter,
        timeRangeFilter: TimeRangeFilter,
        dateRangeFilter: DateRangeFilter,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<SolveTime> {
        // Compute the active date range once for the whole list instead of per solve.
        val now = System.currentTimeMillis()
        val presetDateRange: LongRange? = when (dateRangeFilter.preset) {
            DatePreset.ALL_TIME, DatePreset.CUSTOM -> null
            DatePreset.TODAY -> {
                val todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val todayEnd = LocalDate.now(zoneId).plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
                todayStart..todayEnd
            }
            DatePreset.LAST_7_DAYS ->
                LocalDate.now(zoneId).minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()..now
            DatePreset.LAST_30_DAYS ->
                LocalDate.now(zoneId).minusDays(29).atStartOfDay(zoneId).toInstant().toEpochMilli()..now
        }

        return filter { solve ->
            val matchesPenalty = when (penaltyFilter) {
                PenaltyFilter.ALL -> true
                PenaltyFilter.CLEAN_ONLY -> solve.penalty == Penalty.NONE
                PenaltyFilter.PLUS_TWO_ONLY -> solve.penalty == Penalty.PLUS_TWO
                PenaltyFilter.DNF_ONLY -> solve.penalty == Penalty.DNF
            }
            if (!matchesPenalty) return@filter false

            val min = timeRangeFilter.minDurationMs
            val max = timeRangeFilter.maxDurationMs
            if (min != null || max != null) {
                if (solve.penalty == Penalty.DNF) return@filter false
                val duration = solve.displayTime
                if (min != null && duration < min) return@filter false
                if (max != null && duration > max) return@filter false
            }

            when (dateRangeFilter.preset) {
                DatePreset.ALL_TIME -> true
                DatePreset.TODAY, DatePreset.LAST_7_DAYS, DatePreset.LAST_30_DAYS ->
                    presetDateRange != null && solve.timestamp in presetDateRange
                DatePreset.CUSTOM -> {
                    val start = dateRangeFilter.customStartEpoch
                    val end = dateRangeFilter.customEndEpoch
                    when {
                        start != null && end != null -> solve.timestamp in start..end
                        start != null -> solve.timestamp >= start
                        end != null -> solve.timestamp <= end
                        else -> true
                    }
                }
            }
        }
    }

    private fun List<SolveTime>.sortSolves(order: SolveSortOrder): List<SolveTime> {
        return when (order) {
            SolveSortOrder.MOST_RECENT -> sortedByDescending { it.timestamp }
            SolveSortOrder.OLDEST -> sortedBy { it.timestamp }
            SolveSortOrder.LOWEST_TIME -> sortedWith(
                compareBy<SolveTime> { if (it.penalty == Penalty.DNF) 1 else 0 }
                    .thenBy { it.displayTime }
                    .thenByDescending { it.timestamp }
            )
            SolveSortOrder.HIGHEST_TIME -> sortedWith(
                compareBy<SolveTime> { if (it.penalty == Penalty.DNF) 1 else 0 }
                    .thenByDescending { it.displayTime }
                    .thenByDescending { it.timestamp }
            )
        }
    }

    // --- Session Expand / Collapse Actions ---
    fun toggleSessionExpanded(sessionId: String) {
        _expandedSessionIds.update { current ->
            if (current.contains(sessionId)) current - sessionId else current + sessionId
        }
    }

    fun expandSession(sessionId: String) {
        _expandedSessionIds.update { it + sessionId }
    }

    fun collapseSession(sessionId: String) {
        _expandedSessionIds.update { it - sessionId }
    }

    // --- Multi-Selection Actions ---
    fun startSelection(solveId: String) {
        _selectedSolveDetail.value = null
        _selectedSolveIds.value = setOf(solveId)
    }

    fun toggleSolveSelection(solveId: String) {
        _selectedSolveIds.update { current ->
            if (current.contains(solveId)) current - solveId else current + solveId
        }
    }

    fun selectAllSolves() {
        val visibleSolves = uiState.value.sessionGroups
            .filter { it.isExpanded }
            .flatMap { it.solves }
            .map { it.id }
            .toSet()
            .ifEmpty {
                uiState.value.solves.map { it.id }.toSet()
            }

        if (visibleSolves.isEmpty()) return

        _selectedSolveIds.update { current ->
            if (current.containsAll(visibleSolves)) {
                emptySet()
            } else {
                current + visibleSolves
            }
        }
    }

    fun clearSelection() {
        _selectedSolveIds.value = emptySet()
    }

    fun getSelectedSolves(): List<SolveTime> {
        val ids = _selectedSolveIds.value
        if (ids.isEmpty()) return emptyList()
        val cached = _sessionSolvesCache.value.values.flatten().filter { it.id in ids }
        val trackedSessionIds = _sessionSolvesCache.value.keys
        val flat = _solves.value.filter { it.id in ids && (it.sessionId == null || it.sessionId !in trackedSessionIds) }
        return (cached + flat).distinctBy { it.id }
    }

    // --- Batch Solve Deletion & Undo ---
    fun deleteSelectedSolves() {
        val selectedIds = _selectedSolveIds.value.toList()
        if (selectedIds.isEmpty()) return

        val currentDetailSolveId = _selectedSolveDetail.value?.solve?.id
        if (currentDetailSolveId != null && selectedIds.contains(currentDetailSolveId)) {
            _selectedSolveDetail.value = null
        }

        _selectedSolveIds.value = emptySet()

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                val deleted = solvesRepository.deleteSolvesByIds(selectedIds, ownerId)
                if (deleted.isNotEmpty()) {
                    lastBatchDeletedSolves = deleted
                    _solves.update { list -> list.filter { it.id !in selectedIds } }
                    _totalCount.update { (it - deleted.size).coerceAtLeast(0) }
                    _sessionSolvesCache.update { cache ->
                        cache.mapValues { (_, sList) -> sList.filter { it.id !in selectedIds } }
                    }
                    _effectsChannel.send(HistoryUiEffect.ShowUndoBatchDelete(deletedSolves = deleted))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("No solves were deleted"))
                }
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to delete solves: ${e.message}"))
            }
        }
    }

    fun undoDeleteBatch(deletedSolves: List<SolveTime>? = null) {
        val toRestore = deletedSolves ?: lastBatchDeletedSolves ?: return
        if (toRestore.isEmpty()) return
        lastBatchDeletedSolves = null

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(toRestore, ownerId)
                val restoreIds = toRestore.map { it.id }.toSet()
                _solves.update { list -> toRestore + list.filter { it.id !in restoreIds } }
                _totalCount.update { it + toRestore.size }
                _sessionSolvesCache.update { cache ->
                    var newCache = cache
                    val grouped = toRestore.filter { it.sessionId != null }.groupBy { it.sessionId!! }
                    for ((sId, solvesForSession) in grouped) {
                        val existing = newCache[sId] ?: continue
                        val existingIds = existing.map { it.id }.toSet()
                        val toAdd = solvesForSession.filter { it.id !in existingIds }
                        if (toAdd.isNotEmpty()) {
                            newCache = newCache + (sId to (existing + toAdd).sortedByDescending { it.timestamp })
                        }
                    }
                    newCache
                }
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Restored ${toRestore.size} solves"))
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to restore solves: ${e.message}"))
            }
        }
    }

    // --- Session Deletion & Undo ---
    fun deleteSession(session: Session) {
        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                val snapshot = sessionRepository.deleteSessionWithSolves(session.id, ownerId)
                if (snapshot != null) {
                    _expandedSessionIds.update { it - session.id }
                    expandedSessionJobs.remove(session.id)?.cancel()
                    _sessionSolvesCache.update { it - session.id }
                    _loadingSessionSolves.update { it - session.id }

                    val selectedSolveId = _selectedSolveDetail.value?.solve?.id
                    if (selectedSolveId != null && snapshot.solves.any { it.id == selectedSolveId }) {
                        _selectedSolveDetail.value = null
                    }

                    val deletedSolveIds = snapshot.solves.map { it.id }.toSet()
                    _selectedSolveIds.update { it - deletedSolveIds }

                    lastDeletedSessionSnapshot = snapshot
                    _effectsChannel.send(
                        HistoryUiEffect.ShowUndoSessionDelete(
                            snapshot = snapshot,
                            sessionName = session.name,
                            solvesCount = snapshot.solves.size
                        )
                    )
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("Session not found or already deleted"))
                }
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to delete session: ${e.message}"))
            }
        }
    }

    fun restoreSession(snapshot: DeletedSessionSnapshot) {
        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                sessionRepository.restoreSessionWithSolves(snapshot, ownerId)
                if (lastDeletedSessionSnapshot?.session?.id == snapshot.session.id) {
                    lastDeletedSessionSnapshot = null
                }
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Restored session '${snapshot.session.name}'"))
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to restore session: ${e.message}"))
            }
        }
    }

    fun undoDeleteSession() {
        val snapshot = lastDeletedSessionSnapshot ?: return
        restoreSession(snapshot)
    }

    // --- Delete All Solves & Undo ---
    fun deleteAllSolves() {
        val targetMode = if (_puzzleScope.value == PuzzleScope.ACTIVE_PUZZLE) _currentMode.value else null
        val currentOwner = currentOwnerId

        val previousSelectedSolveIds = _selectedSolveIds.value
        val previousSelectedDetail = _selectedSolveDetail.value
        val previousSolves = _solves.value
        val previousTotalCount = _totalCount.value
        val previousHasMore = _hasMore.value
        val previousOffset = currentOffset
        val previousCache = _sessionSolvesCache.value

        _selectedSolveIds.value = emptySet()
        _selectedSolveDetail.value = null
        _solves.value = emptyList()
        _totalCount.value = 0
        _hasMore.value = false
        currentOffset = 0
        _sessionSolvesCache.value = emptyMap()

        viewModelScope.launch {
            try {
                val deleted = solvesRepository.clearAllSolvesInScope(targetMode, currentOwner)
                if (deleted.isNotEmpty()) {
                    lastClearedAllSolves = deleted
                    _effectsChannel.send(HistoryUiEffect.ShowUndoClearAll(deletedSolves = deleted))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("No solves to clear"))
                }
            } catch (e: Exception) {
                _selectedSolveIds.value = previousSelectedSolveIds
                _selectedSolveDetail.value = previousSelectedDetail
                _solves.value = previousSolves
                _totalCount.value = previousTotalCount
                _hasMore.value = previousHasMore
                currentOffset = previousOffset
                _sessionSolvesCache.value = previousCache
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to clear history: ${e.message}"))
            }
        }
    }

    fun undoDeleteAllSolves(deletedSolves: List<SolveTime>? = null) {
        val toRestore = deletedSolves ?: lastClearedAllSolves ?: return
        if (toRestore.isEmpty()) return
        lastClearedAllSolves = null

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(toRestore, ownerId)
                _solves.value = toRestore
                _totalCount.value = toRestore.size
                currentOffset = toRestore.size
                _hasMore.value = toRestore.size >= PAGE_SIZE
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Restored ${toRestore.size} solves"))
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to restore solves: ${e.message}"))
            }
        }
    }

    // Legacy clear history delegations
    fun clearHistory() = deleteAllSolves()
    fun undoClearHistory() = undoDeleteAllSolves()

    // --- CSV SAF Operations ---
    private suspend fun buildSessionNameLookup(ownerId: String): (String?) -> String {
        val allSessions = effectiveSessionDao.getAllSessionsForOwner(ownerId)
        val map = allSessions.associate { it.id to it.name }
        return { sessionId ->
            when {
                sessionId.isNullOrBlank() -> "General"
                else -> map[sessionId] ?: "Session"
            }
        }
    }

    suspend fun exportAllSolvesToStream(outputStream: OutputStream): Int = withContext(ioDispatcher) {
        val ownerId = currentOwnerId
        val eventFilter = if (_puzzleScope.value == PuzzleScope.ACTIVE_PUZZLE) {
            CubeTypeConverters.fromMode(_currentMode.value)
        } else {
            null
        }
        val entities: List<SolveEntity> = effectiveSolveDao.getSolvesByScope(
            ownerId = ownerId,
            sessionId = null,
            event = eventFilter
        )
        val solves: List<SolveTime> = entities.map { it.toSolveTime() }

        if (solves.isNotEmpty()) {
            val lookup = buildSessionNameLookup(ownerId)
            csvExporter.exportSolves(outputStream, solves, lookup)
        }
        solves.size
    }

    fun exportAllSolves(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val count = withContext(ioDispatcher) {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Unable to open output stream for URI: $uri")
                    outputStream.use { stream ->
                        exportAllSolvesToStream(stream)
                    }
                }
                if (count > 0) {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("Exported $count solves to CSV"))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("No solves to export in current scope"))
                }
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to export solves: ${e.message}"))
            }
        }
    }

    suspend fun exportSessionToStream(session: Session, outputStream: OutputStream): Int = withContext(ioDispatcher) {
        val ownerId = currentOwnerId
        val entities: List<SolveEntity> = effectiveSolveDao.getSolvesBySession(
            ownerId = ownerId,
            sessionId = session.id
        )
        val solves: List<SolveTime> = entities.map { it.toSolveTime() }

        if (solves.isNotEmpty()) {
            val lookup = { sessionId: String? ->
                if (sessionId == session.id) session.name else "General"
            }
            csvExporter.exportSolves(outputStream, solves, lookup)
        }
        solves.size
    }

    fun exportSession(context: Context, session: Session, uri: Uri) {
        viewModelScope.launch {
            try {
                val count = withContext(ioDispatcher) {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Unable to open output stream for URI: $uri")
                    outputStream.use { stream ->
                        exportSessionToStream(session, stream)
                    }
                }
                if (count > 0) {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("Exported $count solves from session '${session.name}' to CSV"))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("No solves to export for session '${session.name}'"))
                }
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to export session: ${e.message}"))
            }
        }
    }

    suspend fun exportSelectedSolvesToStream(outputStream: OutputStream): Int = withContext(ioDispatcher) {
        val selectedIds = _selectedSolveIds.value.toList()
        if (selectedIds.isEmpty()) return@withContext 0

        val ownerId = currentOwnerId
        val entities: List<SolveEntity> = effectiveSolveDao.getSolvesByIds(selectedIds)
            .filter { it.ownerId == ownerId && it.deletedAt == null }
        val solves: List<SolveTime> = entities.map { it.toSolveTime() }

        if (solves.isNotEmpty()) {
            val lookup = buildSessionNameLookup(ownerId)
            csvExporter.exportSolves(outputStream, solves, lookup)
        }
        solves.size
    }

    fun exportSelectedSolves(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val selectedCount = _selectedSolveIds.value.size
                if (selectedCount == 0) {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("No solves selected for export"))
                    return@launch
                }

                val count = withContext(ioDispatcher) {
                    val outputStream = context.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Unable to open output stream for URI: $uri")
                    outputStream.use { stream ->
                        exportSelectedSolvesToStream(stream)
                    }
                }

                _selectedSolveIds.value = emptySet()
                if (count > 0) {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("Exported $count selected solves to CSV"))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage("Selected solves not found"))
                }
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to export selected solves: ${e.message}"))
            }
        }
    }

    suspend fun importSolvesFromStream(inputStream: InputStream): CsvImportStatus = withContext(ioDispatcher) {
        val ownerId = currentOwnerId
        effectiveCsvImporter.importCsv(inputStream, ownerId)
    }

    fun importSolvesFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val status = withContext(ioDispatcher) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open input stream for URI: $uri")
                    inputStream.use { stream ->
                        importSolvesFromStream(stream)
                    }
                }

                when (status) {
                    is CsvImportStatus.Success -> {
                        val message = buildString {
                            append("Imported ${status.importedCount} solves")
                            if (status.duplicateCount > 0) {
                                append(" (${status.duplicateCount} duplicates skipped)")
                            }
                            if (status.malformedCount > 0) {
                                append(" (${status.malformedCount} malformed rows skipped)")
                            }
                        }
                        _effectsChannel.send(HistoryUiEffect.ShowMessage(message))
                        refresh()
                    }
                    is CsvImportStatus.EmptyFile -> {
                        _effectsChannel.send(HistoryUiEffect.ShowMessage("CSV file is empty"))
                    }
                    is CsvImportStatus.InvalidFile -> {
                        _effectsChannel.send(HistoryUiEffect.ShowMessage("Invalid CSV file: ${status.reason}"))
                    }
                    is CsvImportStatus.Error -> {
                        _effectsChannel.send(HistoryUiEffect.ShowMessage("CSV import error: ${status.throwable.message}"))
                    }
                }
            } catch (e: Exception) {
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to import solves: ${e.message}"))
            }
        }
    }

    // --- Filter & Sort Setters & Resets ---
    fun setSessionSort(sort: SessionSortOrder) {
        _sessionSort.value = sort
    }

    fun setPuzzleScope(scope: PuzzleScope) {
        _puzzleScope.value = scope
    }

    fun setSessionKindFilter(filter: SessionKindFilter) {
        _sessionKindFilter.value = filter
    }

    fun setSolveSort(sort: SolveSortOrder) {
        _solveSort.value = sort
    }

    fun setPenaltyFilter(filter: PenaltyFilter) {
        _penaltyFilter.value = filter
    }

    fun setTimeRangeFilter(filter: TimeRangeFilter) {
        _timeRangeFilter.value = filter
    }

    fun setTimeRangeFilter(minDurationMs: Long?, maxDurationMs: Long?) {
        _timeRangeFilter.value = TimeRangeFilter(minDurationMs, maxDurationMs)
    }

    fun setDateRangeFilter(filter: DateRangeFilter) {
        _dateRangeFilter.value = filter
    }

    fun setDateRangeFilter(preset: DatePreset, customStart: Long? = null, customEnd: Long? = null) {
        _dateRangeFilter.value = DateRangeFilter(preset, customStart, customEnd)
    }

    fun openFilterSheet(initialTab: Int = 0) {
        _activeFilterSheetTab.value = initialTab.coerceIn(0, 1)
        _isFilterSheetOpen.value = true
    }

    fun closeFilterSheet() {
        _isFilterSheetOpen.value = false
    }

    fun setActiveFilterSheetTab(tabIndex: Int) {
        _activeFilterSheetTab.value = tabIndex.coerceIn(0, 1)
    }

    fun resetAllFilters() {
        _sessionSort.value = SessionSortOrder.MOST_RECENT
        _puzzleScope.value = PuzzleScope.ACTIVE_PUZZLE
        _sessionKindFilter.value = SessionKindFilter.ALL
        _solveSort.value = SolveSortOrder.MOST_RECENT
        _penaltyFilter.value = PenaltyFilter.ALL
        _timeRangeFilter.value = TimeRangeFilter()
        _dateRangeFilter.value = DateRangeFilter()
    }

    fun resetSessionFilters() {
        _sessionSort.value = SessionSortOrder.MOST_RECENT
        _puzzleScope.value = PuzzleScope.ACTIVE_PUZZLE
        _sessionKindFilter.value = SessionKindFilter.ALL
    }

    fun resetSolveFilters() {
        _solveSort.value = SolveSortOrder.MOST_RECENT
        _penaltyFilter.value = PenaltyFilter.ALL
        _timeRangeFilter.value = TimeRangeFilter()
        _dateRangeFilter.value = DateRangeFilter()
    }

    // --- Legacy / Flat Solves Actions & Pagination ---
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

        // Optimistically update solves cache for expanded session groups
        _sessionSolvesCache.update { cache ->
            cache.mapValues { (_, sList) ->
                sList.map { if (it.id == solve.id) updatedSolve else it }
            }
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
                _sessionSolvesCache.update { cache ->
                    cache.mapValues { (_, sList) ->
                        sList.map { if (it.id == solve.id) it.copy(penalty = previousPenalty) else it }
                    }
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
        if (index == -1 && !_sessionSolvesCache.value.values.any { list -> list.any { it.id == solve.id } }) {
            return
        }

        if (_selectedSolveDetail.value?.solve?.id == solve.id) {
            _selectedSolveDetail.value = null
        }

        lastDeletedSolve = solve
        lastDeletedIndex = if (index != -1) index else 0

        _solves.value = _solves.value.filter { it.id != solve.id }
        _totalCount.value = (_totalCount.value - 1).coerceAtLeast(0)
        currentOffset = (currentOffset - 1).coerceAtLeast(0)
        _sessionSolvesCache.update { cache ->
            cache.mapValues { (_, sList) -> sList.filter { it.id != solve.id } }
        }
        _selectedSolveIds.update { it - solve.id }

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.deleteSolve(solve, ownerId = ownerId)
                _effectsChannel.send(
                    HistoryUiEffect.ShowUndoSnackbar(
                        message = "Solve deleted",
                        solve = solve,
                        originalIndex = lastDeletedIndex ?: 0
                    )
                )
            } catch (e: Exception) {
                if (index != -1) {
                    val mutable = _solves.value.toMutableList()
                    mutable.add(index.coerceIn(0, mutable.size), solve)
                    _solves.value = mutable
                    _totalCount.value += 1
                    currentOffset += 1
                }
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
        val sId = solve.sessionId
        if (sId != null) {
            _sessionSolvesCache.update { cache ->
                val existing = cache[sId] ?: return@update cache
                if (existing.none { it.id == solve.id }) {
                    cache + (sId to (listOf(solve) + existing).sortedByDescending { it.timestamp })
                } else cache
            }
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
                if (sId != null) {
                    _sessionSolvesCache.update { cache ->
                        val existing = cache[sId] ?: return@update cache
                        cache + (sId to existing.filter { it.id != solve.id })
                    }
                }
                _effectsChannel.send(HistoryUiEffect.ShowMessage("Failed to restore solve: ${e.message}"))
            }
        }
    }

    fun undoDelete() {
        val solveToRestore = lastDeletedSolve ?: return
        restoreSolve(solveToRestore)
    }

    /**
     * Opens the solve detail card. [solveNumber] should be passed when the caller knows the solve's
     * position (e.g. from a session group); otherwise it is derived from the flat solves list.
     */
    fun selectSolveForDetail(solve: SolveTime, solveNumber: Int? = null) {
        viewModelScope.launch {
            val ownerId = currentOwnerId
            val solvedAtIso = CubeTypeConverters.epochMillisToIso(solve.timestamp)
            val priorBestTime = solvesRepository.getPriorBestSolveDuration(
                mode = solve.mode,
                solvedAtIso = solvedAtIso,
                ownerId = ownerId,
                excludeSolveId = solve.id
            )

            val resolvedSolveNumber = solveNumber ?: run {
                val solveIndex = _solves.value.indexOfFirst { it.id == solve.id }
                if (solveIndex != -1) (_totalCount.value - solveIndex).coerceAtLeast(1) else 1
            }

            val pbResult = HistoricalPbCalculator.calculate(
                solve = solve,
                priorBestDurationMs = priorBestTime
            )

            _selectedSolveDetail.value = SolveDetailState(
                solve = solve,
                solveNumber = resolvedSolveNumber,
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
