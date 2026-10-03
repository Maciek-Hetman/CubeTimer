package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.CubeTimerApplication
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.dao.SessionDao
import com.maciekhetman.cubetimer.data.local.dao.SolveDao
import com.maciekhetman.cubetimer.data.local.dao.SyncOutboxDao
import com.maciekhetman.cubetimer.data.local.dao.getSolvesByIdsChunked
import com.maciekhetman.cubetimer.data.local.dto.SessionWithStats
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.local.mapper.*
import com.maciekhetman.cubetimer.data.session.DeletedSessionSnapshot
import com.maciekhetman.cubetimer.data.session.SessionRepository
import com.maciekhetman.cubetimer.domain.HistoricalPbCalculator
import com.maciekhetman.cubetimer.domain.csv.CsvExporter
import com.maciekhetman.cubetimer.domain.csv.CsvImportStatus
import com.maciekhetman.cubetimer.domain.csv.CsvImporter
import com.maciekhetman.cubetimer.model.*
import com.maciekhetman.cubetimer.ui.components.displaySessionName
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
    val solveSort: SolveSortOrder,
    val penaltyFilter: PenaltyFilter,
    val timeRangeFilter: TimeRangeFilter,
    val dateRangeFilter: DateRangeFilter,
    val isFilterSheetOpen: Boolean,
    val activeFilterSheetTab: Int
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    application: Application,
    private val solvesRepository: SolvesRepository,
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

    constructor(application: Application) : this(
        application = application,
        solvesRepository = (application as CubeTimerApplication).solvesRepository,
        sessionRepository = application.sessionRepository,
        authManager = application.authManager,
        csvImporter = application.csvImporter
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

    private val _currentMode = MutableStateFlow(Mode.CUBE_3x3)
    val currentMode: StateFlow<Mode> = _currentMode.asStateFlow()

    private val _effectsChannel = Channel<HistoryUiEffect>(Channel.BUFFERED)
    val effects: Flow<HistoryUiEffect> = _effectsChannel.receiveAsFlow()

    private val _selectedSolveDetail = MutableStateFlow<SolveDetailState?>(null)

    private val currentOwnerId: String get() = authManager.currentOwnerId

    private var solveDetailJob: Job? = null
    private var solveDetailGeneration = 0

    // --- Reactive Session Groups Flow ---
    private val rawSessionsWithStats: Flow<List<SessionWithStats>> = combine(
        authManager.authState,
        _currentMode,
        _puzzleScope
    ) { authState, mode, scope ->
        val ownerId = authState.ownerId
        val event = if (scope == PuzzleScope.ACTIVE_PUZZLE) CubeTypeConverters.fromMode(mode) else null
        ownerId to event
    }.distinctUntilChanged().flatMapLatest { (ownerId, event) ->
        effectiveSessionDao.observeSessionsWithStats(ownerId, event, null)
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
            combine(_sessionSort, _puzzleScope) { sSort, pScope ->
                Pair(sSort, pScope)
            },
            combine(_solveSort, _penaltyFilter, _timeRangeFilter, _dateRangeFilter) { slvSort, pFilter, tFilter, dFilter ->
                SolveFilterQuad(slvSort, pFilter, tFilter, dFilter)
            },
            combine(_isFilterSheetOpen, _activeFilterSheetTab) { open, tab ->
                Pair(open, tab)
            }
        ) { (sSort, pScope), (slvSort, pFilter, tFilter, dFilter), (open, tab) ->
            FilterStateChunk(
                sessionSort = sSort,
                puzzleScope = pScope,
                solveSort = slvSort,
                penaltyFilter = pFilter,
                timeRangeFilter = tFilter,
                dateRangeFilter = dFilter,
                isFilterSheetOpen = open,
                activeFilterSheetTab = tab
            )
        },
        _selectedSolveDetail
    ) { groups: GroupStateChunk, filters: FilterStateChunk, selectedSolve: SolveDetailState? ->
        HistoryUiState(
            sessionGroups = groups.sessionGroups,
            expandedSessionIds = groups.expandedSessionIds,
            selectedSolveIds = groups.selectedSolveIds,
            sessionSort = filters.sessionSort,
            puzzleScope = filters.puzzleScope,
            solveSort = filters.solveSort,
            penaltyFilter = filters.penaltyFilter,
            timeRangeFilter = filters.timeRangeFilter,
            dateRangeFilter = filters.dateRangeFilter,
            isFilterSheetOpen = filters.isFilterSheetOpen,
            activeFilterSheetTab = filters.activeFilterSheetTab,
            // The combine only emits once the session list has been read, so loading is over.
            isLoading = false,
            selectedSolve = selectedSolve
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    init {
        observeExpandedSessions()
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
        // Only solves the user can actually see: the expanded, filtered session groups.
        val visibleSolves = uiState.value.sessionGroups
            .filter { it.isExpanded }
            .flatMap { it.solves }
            .map { it.id }
            .toSet()

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
                    _sessionSolvesCache.update { cache ->
                        cache.mapValues { (_, sList) -> sList.filter { it.id !in selectedIds } }
                    }
                    _effectsChannel.send(
                        HistoryUiEffect.ShowUndoBatchDelete(
                            deletedSolves = deleted,
                            message = quantityText(R.plurals.history_deleted_solves, deleted.size)
                        )
                    )
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_no_solves_deleted)))
                }
            } catch (e: Exception) {
                reportFailure(R.string.history_error_delete_solves, e)
            }
        }
    }

    fun undoDeleteBatch(deletedSolves: List<SolveTime>) {
        if (deletedSolves.isEmpty()) return

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(deletedSolves, ownerId)
                _sessionSolvesCache.update { cache ->
                    var newCache = cache
                    val grouped = deletedSolves.filter { it.sessionId != null }.groupBy { it.sessionId!! }
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
                _effectsChannel.send(HistoryUiEffect.ShowMessage(quantityText(R.plurals.history_restored_solves, deletedSolves.size)))
            } catch (e: Exception) {
                reportFailure(R.string.history_error_restore_solves, e)
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

                    _effectsChannel.send(
                        HistoryUiEffect.ShowUndoSessionDelete(
                            snapshot = snapshot,
                            sessionName = session.name,
                            solvesCount = snapshot.solves.size
                        )
                    )
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_session_not_found)))
                }
            } catch (e: Exception) {
                reportFailure(R.string.history_error_delete_session, e)
            }
        }
    }

    fun restoreSession(snapshot: DeletedSessionSnapshot) {
        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                sessionRepository.restoreSessionWithSolves(snapshot, ownerId)
                _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_session_restored, sessionName(snapshot.session.name))))
            } catch (e: Exception) {
                reportFailure(R.string.history_error_restore_session, e)
            }
        }
    }

    // --- Delete All Solves & Undo ---
    fun deleteAllSolves() {
        val targetMode = if (_puzzleScope.value == PuzzleScope.ACTIVE_PUZZLE) _currentMode.value else null
        val currentOwner = currentOwnerId

        val previousSelectedSolveIds = _selectedSolveIds.value
        val previousSelectedDetail = _selectedSolveDetail.value
        val previousCache = _sessionSolvesCache.value

        _selectedSolveIds.value = emptySet()
        _selectedSolveDetail.value = null
        _sessionSolvesCache.value = emptyMap()

        viewModelScope.launch {
            try {
                val deleted = solvesRepository.clearAllSolvesInScope(targetMode, currentOwner)
                if (deleted.isNotEmpty()) {
                    _effectsChannel.send(
                        HistoryUiEffect.ShowUndoClearAll(
                            deletedSolves = deleted,
                            message = quantityText(R.plurals.history_cleared_solves, deleted.size)
                        )
                    )
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_no_solves_to_clear)))
                }
            } catch (e: Exception) {
                _selectedSolveIds.value = previousSelectedSolveIds
                _selectedSolveDetail.value = previousSelectedDetail
                _sessionSolvesCache.value = previousCache
                reportFailure(R.string.history_error_clear, e)
            }
        }
    }

    fun undoDeleteAllSolves(deletedSolves: List<SolveTime>) {
        if (deletedSolves.isEmpty()) return

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(deletedSolves, ownerId)
                _effectsChannel.send(HistoryUiEffect.ShowMessage(quantityText(R.plurals.history_restored_solves, deletedSolves.size)))
            } catch (e: Exception) {
                reportFailure(R.string.history_error_restore_solves, e)
            }
        }
    }

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
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(quantityText(R.plurals.history_exported_solves, count)))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_nothing_to_export)))
                }
            } catch (e: Exception) {
                reportFailure(R.string.history_error_export, e)
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
                    _effectsChannel.send(
                        HistoryUiEffect.ShowMessage(quantityText(R.plurals.history_exported_session, count, sessionName(session.name)))
                    )
                } else {
                    _effectsChannel.send(
                        HistoryUiEffect.ShowMessage(text(R.string.history_nothing_to_export_session, sessionName(session.name)))
                    )
                }
            } catch (e: Exception) {
                reportFailure(R.string.history_error_export_session, e)
            }
        }
    }

    suspend fun exportSelectedSolvesToStream(outputStream: OutputStream): Int = withContext(ioDispatcher) {
        val selectedIds = _selectedSolveIds.value.toList()
        if (selectedIds.isEmpty()) return@withContext 0

        val ownerId = currentOwnerId
        val entities: List<SolveEntity> = effectiveSolveDao.getSolvesByIdsChunked(selectedIds)
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
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_nothing_selected_to_export)))
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
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(quantityText(R.plurals.history_exported_selected, count)))
                } else {
                    _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_selected_not_found)))
                }
            } catch (e: Exception) {
                reportFailure(R.string.history_error_export_selected, e)
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
                            append(quantityText(R.plurals.history_imported_solves, status.importedCount))
                            if (status.duplicateCount > 0) {
                                append(" (")
                                append(quantityText(R.plurals.history_import_duplicates_skipped, status.duplicateCount))
                                append(")")
                            }
                            if (status.malformedCount > 0) {
                                append(" (")
                                append(quantityText(R.plurals.history_import_malformed_skipped, status.malformedCount))
                                append(")")
                            }
                        }
                        _effectsChannel.send(HistoryUiEffect.ShowMessage(message))
                    }
                    is CsvImportStatus.EmptyFile -> {
                        _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_import_empty)))
                    }
                    is CsvImportStatus.InvalidFile -> {
                        _effectsChannel.send(HistoryUiEffect.ShowMessage(text(R.string.history_import_invalid, invalidCsvReason(status))))
                    }
                    is CsvImportStatus.Error -> {
                        reportFailure(R.string.history_error_import_csv, status.throwable)
                    }
                }
            } catch (e: Exception) {
                reportFailure(R.string.history_error_import, e)
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
        _solveSort.value = SolveSortOrder.MOST_RECENT
        _penaltyFilter.value = PenaltyFilter.ALL
        _timeRangeFilter.value = TimeRangeFilter()
        _dateRangeFilter.value = DateRangeFilter()
    }

    fun setMode(mode: Mode) {
        if (_currentMode.value != mode) {
            _currentMode.value = mode
        }
    }

    fun updateSolvePenalty(solve: SolveTime, penalty: Penalty) {
        val previousPenalty = solve.penalty
        if (previousPenalty == penalty) return

        val updatedSolve = solve.copy(penalty = penalty)

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
                pbDelta = newPbResult.deltaMs
            )
        }

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.updateSolvePenalty(solve, penalty, ownerId = ownerId)
            } catch (e: Exception) {
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
                        pbDelta = revertedPbResult.deltaMs
                    )
                }
                reportFailure(R.string.history_error_update_penalty, e)
            }
        }
    }

    fun deleteSolve(solve: SolveTime) {
        if (_sessionSolvesCache.value.values.none { list -> list.any { it.id == solve.id } }) return

        if (_selectedSolveDetail.value?.solve?.id == solve.id) {
            _selectedSolveDetail.value = null
        }

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
                        message = text(R.string.history_solve_deleted),
                        solve = solve
                    )
                )
            } catch (e: Exception) {
                val sId = solve.sessionId
                if (sId != null) {
                    _sessionSolvesCache.update { cache ->
                        val existing = cache[sId] ?: return@update cache
                        if (existing.none { it.id == solve.id }) {
                            cache + (sId to (existing + solve).sortedByDescending { it.timestamp })
                        } else cache
                    }
                }
                reportFailure(R.string.history_error_delete_solve, e)
            }
        }
    }

    fun restoreSolve(solve: SolveTime) {
        val sId = solve.sessionId
        if (sId != null) {
            _sessionSolvesCache.update { cache ->
                val existing = cache[sId] ?: return@update cache
                if (existing.none { it.id == solve.id }) {
                    cache + (sId to (listOf(solve) + existing).sortedByDescending { it.timestamp })
                } else cache
            }
        }

        viewModelScope.launch {
            try {
                val ownerId = currentOwnerId
                solvesRepository.restoreSolves(listOf(solve), ownerId = ownerId)
            } catch (e: Exception) {
                if (sId != null) {
                    _sessionSolvesCache.update { cache ->
                        val existing = cache[sId] ?: return@update cache
                        cache + (sId to existing.filter { it.id != solve.id })
                    }
                }
                reportFailure(R.string.history_error_restore_solve, e)
            }
        }
    }

    /** Opens the solve detail card; [solveNumber] is the solve's position within its session. */
    fun selectSolveForDetail(solve: SolveTime, solveNumber: Int) {
        val generation = ++solveDetailGeneration
        solveDetailJob?.cancel()
        solveDetailJob = viewModelScope.launch {
            val ownerId = currentOwnerId
            val solvedAtIso = CubeTypeConverters.epochMillisToIso(solve.timestamp)
            val priorBestTime = solvesRepository.getPriorBestSolveDuration(
                mode = solve.mode,
                solvedAtIso = solvedAtIso,
                ownerId = ownerId,
                excludeSolveId = solve.id
            )
            // A newer tap, or a dismiss, happened while the lookup was in flight.
            if (generation != solveDetailGeneration) return@launch

            val pbResult = HistoricalPbCalculator.calculate(
                solve = solve,
                priorBestDurationMs = priorBestTime
            )

            _selectedSolveDetail.value = SolveDetailState(
                solve = solve,
                solveNumber = solveNumber,
                priorBestTime = priorBestTime,
                isPb = pbResult.isPb,
                pbDelta = pbResult.deltaMs
            )
        }
    }

    fun dismissSolveDetail() {
        solveDetailGeneration++
        solveDetailJob?.cancel()
        _selectedSolveDetail.value = null
    }

    /** Shows a fixed, human-readable [message] and keeps the technical detail in the log. */
    private suspend fun reportFailure(@StringRes messageRes: Int, error: Throwable) {
        val message = text(messageRes)
        Log.e(TAG, message, error)
        _effectsChannel.send(HistoryUiEffect.ShowMessage(message))
    }

    private fun text(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /** [count] is both the plural selector and the first format argument. */
    private fun quantityText(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        getApplication<Application>().resources.getQuantityString(id, count, count, *args)

    private fun sessionName(name: String): String =
        displaySessionName(getApplication<Application>().resources, name)

    private fun invalidCsvReason(status: CsvImportStatus.InvalidFile): String = when (status.problem) {
        CsvImportStatus.InvalidFile.Problem.MISSING_HEADER_ROW -> text(R.string.csv_problem_missing_header_row)
        CsvImportStatus.InvalidFile.Problem.INVALID_COMMENT -> text(R.string.csv_problem_invalid_comment)
        CsvImportStatus.InvalidFile.Problem.MISSING_SOURCE_COMMENT -> text(R.string.csv_problem_missing_source_comment)
        CsvImportStatus.InvalidFile.Problem.MISSING_COLUMNS ->
            text(R.string.csv_problem_missing_columns, status.missingColumns.joinToString())
        CsvImportStatus.InvalidFile.Problem.OTHER -> status.reason
    }

    private companion object {
        const val TAG = "HistoryViewModel"
    }
}
