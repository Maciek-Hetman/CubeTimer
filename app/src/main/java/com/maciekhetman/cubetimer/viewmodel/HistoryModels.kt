package com.maciekhetman.cubetimer.viewmodel

import com.maciekhetman.cubetimer.data.session.DeletedSessionSnapshot
import com.maciekhetman.cubetimer.domain.HistoricalPbResult
import com.maciekhetman.cubetimer.domain.TimeFormatter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.StatsFilter

/**
 * Ordering options for session groups on HistoryScreen.
 */
enum class SessionSortOrder(val displayName: String) {
    MOST_RECENT("Most Recent"),
    OLDEST("Oldest"),
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    MOST_SOLVES("Most Solves")
}

/**
 * Scope filter determining whether sessions are filtered to the current timer puzzle mode
 * or shown across all puzzles.
 */
enum class PuzzleScope(val displayName: String) {
    ACTIVE_PUZZLE("Active Puzzle"),
    ALL_PUZZLES("All Puzzles")
}

/**
 * Filter determining whether all sessions, manual sessions only, or automatic sessions only are shown.
 */
enum class SessionKindFilter(val displayName: String) {
    ALL("All"),
    MANUAL_ONLY("Manual Only"),
    AUTOMATIC_ONLY("Automatic Only")
}

/**
 * Ordering options for individual solves displayed inside expanded session groups.
 */
enum class SolveSortOrder(val displayName: String) {
    MOST_RECENT("Most Recent"),
    OLDEST("Oldest"),
    LOWEST_TIME("Lowest Time (Fastest)"),
    HIGHEST_TIME("Highest Time (Slowest)");

    companion object {
        val FASTEST = LOWEST_TIME
        val SLOWEST = HIGHEST_TIME
    }
}

/**
 * Filter options for solve penalties.
 */
enum class PenaltyFilter(val displayName: String) {
    ALL("All"),
    CLEAN_ONLY("Clean Only"),
    PLUS_TWO_ONLY("+2 Only"),
    DNF_ONLY("DNF Only");

    companion object {
        val CLEAN = CLEAN_ONLY
        val PLUS_TWO = PLUS_TWO_ONLY
        val DNF = DNF_ONLY
    }
}

/**
 * Preset time boundaries for date range filtering.
 */
enum class DatePreset(val displayName: String) {
    ALL_TIME("All Time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
    CUSTOM("Custom")
}

/**
 * Numerical duration filter bounding solve durations in milliseconds.
 */
data class TimeRangeFilter(
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null
) {
    val isActive: Boolean get() = minDurationMs != null || maxDurationMs != null
}

/**
 * Date range filter specifying either a preset or custom epoch millisecond bounds.
 */
data class DateRangeFilter(
    val preset: DatePreset = DatePreset.ALL_TIME,
    val customStartEpoch: Long? = null,
    val customEndEpoch: Long? = null
) {
    val isActive: Boolean get() = preset != DatePreset.ALL_TIME
}

/**
 * UI presentation model for an expandable session group card.
 */
data class SessionGroupUiModel(
    val session: Session,
    val solveCount: Int,
    val bestDurationMs: Long?,
    val avgDurationMs: Long?,
    val isExpanded: Boolean = false,
    val solves: List<SolveTime> = emptyList(),
    val isSolvesLoading: Boolean = false,
    /** Chronological 1-based solve number per solve id, independent of the active solve sort/filter. */
    val solveNumbers: Map<String, Int> = emptyMap()
) {
    val id: String get() = session.id
    val name: String get() = session.name
    val event: Mode get() = session.event
    val kind: SessionKind get() = session.kind
    val startedAt: String get() = session.startedAt
    val isOpen: Boolean get() = session.isOpen
    val isEmpty: Boolean get() = solveCount == 0

    val averageDurationMs: Long? get() = avgDurationMs

    val formattedBest: String?
        get() = bestDurationMs?.let { TimeFormatter.formatTime(it) }

    val formattedAvg: String?
        get() = avgDurationMs?.let { TimeFormatter.formatTime(it) }
}

/**
 * UI State representing the complete presentation state of HistoryScreen.
 */
data class HistoryUiState(
    // Session list & grouping
    val sessionGroups: List<SessionGroupUiModel> = emptyList(),
    val expandedSessionIds: Set<String> = emptySet(),

    // Multi-select batch state
    val selectedSolveIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = selectedSolveIds.isNotEmpty(),

    // Filtering & Sorting - Sessions Tab
    val sessionSort: SessionSortOrder = SessionSortOrder.MOST_RECENT,
    val puzzleScope: PuzzleScope = PuzzleScope.ACTIVE_PUZZLE,
    val sessionKindFilter: SessionKindFilter = SessionKindFilter.ALL,

    // Filtering & Sorting - Solves Tab
    val solveSort: SolveSortOrder = SolveSortOrder.MOST_RECENT,
    val penaltyFilter: PenaltyFilter = PenaltyFilter.ALL,
    val timeRangeFilter: TimeRangeFilter = TimeRangeFilter(),
    val dateRangeFilter: DateRangeFilter = DateRangeFilter(),

    // Filter bottom sheet UI state
    val isFilterSheetOpen: Boolean = false,
    val activeFilterSheetTab: Int = 0, // 0 = Sessions, 1 = Solves

    // Preserved fields for backwards compatibility
    val solves: List<SolveTime> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
    val activeSessionCount: Int = 0,
    val allSolvesCount: Int = 0,
    val currentMode: Mode = Mode.CUBE_3x3,
    val currentFilter: StatsFilter = StatsFilter.AllSessions,
    val activeSession: Session? = null,
    val sessions: List<Session> = emptyList(),
    val errorMessage: String? = null,
    val selectedSolve: SolveDetailState? = null
) {
    val activeSessionFilterCount: Int
        get() = (if (sessionSort != SessionSortOrder.MOST_RECENT) 1 else 0) +
                (if (puzzleScope != PuzzleScope.ACTIVE_PUZZLE) 1 else 0) +
                (if (sessionKindFilter != SessionKindFilter.ALL) 1 else 0)

    val activeSolveFilterCount: Int
        get() = (if (solveSort != SolveSortOrder.MOST_RECENT) 1 else 0) +
                (if (penaltyFilter != PenaltyFilter.ALL) 1 else 0) +
                (if (timeRangeFilter.isActive) 1 else 0) +
                (if (dateRangeFilter.isActive) 1 else 0)

    val totalActiveFilterCount: Int
        get() = activeSessionFilterCount + activeSolveFilterCount

    val isInitialLoading: Boolean get() = isLoading
    val activeSessionSolvesCount: Int get() = activeSessionCount
}

/**
 * One-shot UI side effects.
 */
interface HistoryUiEffect {
    data class ShowUndoSnackbar(
        val message: String,
        val solve: SolveTime,
        val originalIndex: Int
    ) : HistoryUiEffect

    data class ShowUndoSessionDelete(
        val snapshot: DeletedSessionSnapshot,
        val sessionName: String,
        val solvesCount: Int
    ) : HistoryUiEffect

    data class ShowUndoBatchDelete(
        val deletedSolves: List<SolveTime>,
        val message: String = "Deleted ${deletedSolves.size} solves"
    ) : HistoryUiEffect

    data class ShowUndoClearAll(
        val deletedSolves: List<SolveTime>,
        val message: String = "Cleared ${deletedSolves.size} solves"
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
