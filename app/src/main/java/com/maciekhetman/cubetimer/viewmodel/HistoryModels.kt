package com.maciekhetman.cubetimer.viewmodel

import androidx.annotation.StringRes
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.data.session.DeletedSessionSnapshot
import com.maciekhetman.cubetimer.domain.TimeFormatter
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime

/**
 * Ordering options for session groups on HistoryScreen.
 */
enum class SessionSortOrder(@StringRes val labelRes: Int) {
    MOST_RECENT(R.string.sort_most_recent),
    OLDEST(R.string.sort_oldest),
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc),
    MOST_SOLVES(R.string.sort_most_solves)
}

/**
 * Scope filter determining whether sessions are filtered to the current timer puzzle mode
 * or shown across all puzzles.
 */
enum class PuzzleScope(@StringRes val labelRes: Int) {
    ACTIVE_PUZZLE(R.string.puzzle_scope_current),
    ALL_PUZZLES(R.string.puzzle_scope_all)
}

/**
 * Ordering options for individual solves displayed inside expanded session groups.
 */
enum class SolveSortOrder(@StringRes val labelRes: Int) {
    MOST_RECENT(R.string.sort_most_recent),
    OLDEST(R.string.sort_oldest),
    LOWEST_TIME(R.string.sort_fastest),
    HIGHEST_TIME(R.string.sort_slowest)
}

/**
 * Filter options for solve penalties.
 */
enum class PenaltyFilter(@StringRes val labelRes: Int) {
    ALL(R.string.penalty_filter_all),
    CLEAN_ONLY(R.string.penalty_filter_clean),
    PLUS_TWO_ONLY(R.string.penalty_plus_two),
    DNF_ONLY(R.string.penalty_dnf)
}

/**
 * Preset time boundaries for date range filtering.
 */
enum class DatePreset(@StringRes val labelRes: Int) {
    ALL_TIME(R.string.date_preset_all_time),
    TODAY(R.string.date_preset_today),
    LAST_7_DAYS(R.string.date_preset_last_7_days),
    LAST_30_DAYS(R.string.date_preset_last_30_days),
    CUSTOM(R.string.date_preset_custom)
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

    // Filtering & Sorting - Solves Tab
    val solveSort: SolveSortOrder = SolveSortOrder.MOST_RECENT,
    val penaltyFilter: PenaltyFilter = PenaltyFilter.ALL,
    val timeRangeFilter: TimeRangeFilter = TimeRangeFilter(),
    val dateRangeFilter: DateRangeFilter = DateRangeFilter(),

    // Filter bottom sheet UI state
    val isFilterSheetOpen: Boolean = false,
    val activeFilterSheetTab: Int = 0, // 0 = Sessions, 1 = Solves

    // True until the session list has been read for the first time.
    val isLoading: Boolean = true,
    val selectedSolve: SolveDetailState? = null
) {
    val activeSessionFilterCount: Int
        get() = (if (sessionSort != SessionSortOrder.MOST_RECENT) 1 else 0) +
                (if (puzzleScope != PuzzleScope.ACTIVE_PUZZLE) 1 else 0)

    val activeSolveFilterCount: Int
        get() = (if (solveSort != SolveSortOrder.MOST_RECENT) 1 else 0) +
                (if (penaltyFilter != PenaltyFilter.ALL) 1 else 0) +
                (if (timeRangeFilter.isActive) 1 else 0) +
                (if (dateRangeFilter.isActive) 1 else 0)

    val totalActiveFilterCount: Int
        get() = activeSessionFilterCount + activeSolveFilterCount
}

/**
 * One-shot UI side effects.
 */
interface HistoryUiEffect {
    data class ShowUndoSnackbar(
        val message: String,
        val solve: SolveTime
    ) : HistoryUiEffect

    data class ShowUndoSessionDelete(
        val snapshot: DeletedSessionSnapshot,
        val sessionName: String,
        val solvesCount: Int
    ) : HistoryUiEffect

    data class ShowUndoBatchDelete(
        val deletedSolves: List<SolveTime>,
        val message: String
    ) : HistoryUiEffect

    data class ShowUndoClearAll(
        val deletedSolves: List<SolveTime>,
        val message: String
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
    val pbDelta: Long?
)
