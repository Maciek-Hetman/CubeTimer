package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.ui.components.HistoryContextualTopAppBar
import com.maciekhetman.cubetimer.ui.components.SessionCardActions
import com.maciekhetman.cubetimer.ui.components.SessionCardHeader
import com.maciekhetman.cubetimer.ui.components.SessionCardSolveRow
import com.maciekhetman.cubetimer.ui.dialogs.HistoryFilterSortBottomSheet
import com.maciekhetman.cubetimer.viewmodel.DatePreset
import com.maciekhetman.cubetimer.viewmodel.DateRangeFilter
import com.maciekhetman.cubetimer.viewmodel.HistoryUiState
import com.maciekhetman.cubetimer.viewmodel.PenaltyFilter
import com.maciekhetman.cubetimer.viewmodel.PuzzleScope
import com.maciekhetman.cubetimer.viewmodel.SessionGroupUiModel
import com.maciekhetman.cubetimer.viewmodel.SessionSortOrder
import com.maciekhetman.cubetimer.viewmodel.SolveSortOrder
import com.maciekhetman.cubetimer.viewmodel.TimeRangeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryScreenM4Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createSession(
        name: String = "Test Session",
        event: Mode = Mode.CUBE_3x3,
        kind: SessionKind = SessionKind.MANUAL
    ): Session {
        return Session(
            id = UUID.randomUUID().toString(),
            ownerId = "guest",
            name = name,
            event = event,
            kind = kind,
            startedAt = "2026-09-12T10:00:00Z"
        )
    }

    private fun createSolve(
        timeInMillis: Long = 12500L,
        penalty: Penalty = Penalty.NONE,
        sessionId: String = UUID.randomUUID().toString()
    ): SolveTime {
        return SolveTime(
            id = UUID.randomUUID().toString(),
            timeInMillis = timeInMillis,
            timestamp = 1726135200000L,
            scramble = "R U R' U'",
            mode = Mode.CUBE_3x3,
            penalty = penalty,
            sessionId = sessionId
        )
    }

    // --- 1. HistoryContextualTopAppBar Tests ---

    @Test
    fun testHistoryContextualTopAppBar_displaysSelectedCount_andInvokesActions() {
        var dismissClicked = false
        var selectAllClicked = false
        var exportClicked = false
        var deleteClicked = false

        composeTestRule.setContent {
            MaterialTheme {
                HistoryContextualTopAppBar(
                    selectedCount = 4,
                    isAllSelected = false,
                    onDismiss = { dismissClicked = true },
                    onSelectAllToggle = { selectAllClicked = true },
                    onExportSelected = { exportClicked = true },
                    onDeleteSelected = { deleteClicked = true }
                )
            }
        }

        // Title displaying count
        composeTestRule.onNodeWithText("4 selected").assertIsDisplayed()

        // Cancel selection
        composeTestRule.onNodeWithContentDescription("Cancel selection").performClick()
        assertTrue(dismissClicked)

        // Select all
        composeTestRule.onNodeWithContentDescription("Select All").performClick()
        assertTrue(selectAllClicked)

        // Export selected
        composeTestRule.onNodeWithContentDescription("Export selected solves").performClick()
        assertTrue(exportClicked)

        // Delete selected
        composeTestRule.onNodeWithContentDescription("Delete selected solves").performClick()
        assertTrue(deleteClicked)
    }

    @Test
    fun testHistoryContextualTopAppBar_whenAllSelected_showsDeselectAllDescription() {
        composeTestRule.setContent {
            MaterialTheme {
                HistoryContextualTopAppBar(
                    selectedCount = 10,
                    isAllSelected = true,
                    onDismiss = {},
                    onSelectAllToggle = {},
                    onExportSelected = {},
                    onDeleteSelected = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Deselect All").assertIsDisplayed()
    }

    // --- 2. Session card pieces (header, solve rows, actions) ---

    @Test
    fun testSessionCardHeader_rendersHeaderInfo_andTogglesExpansion() {
        val session = createSession(name = "Main Practice 3x3", event = Mode.CUBE_3x3, kind = SessionKind.MANUAL)
        val group = SessionGroupUiModel(
            session = session,
            solveCount = 25,
            bestDurationMs = 8420L,
            avgDurationMs = 11350L,
            isExpanded = false
        )

        var expanded by mutableStateOf(false)

        composeTestRule.setContent {
            MaterialTheme {
                SessionCardHeader(
                    sessionGroup = group.copy(isExpanded = expanded),
                    expanded = expanded,
                    onClick = { expanded = !expanded }
                )
            }
        }

        // Header: name, "count · event · kind" summary, best and average
        composeTestRule.onNodeWithText("Main Practice 3x3").assertIsDisplayed()
        composeTestRule.onNodeWithText("25 solves · 3x3").assertIsDisplayed()
        composeTestRule.onNodeWithText("8.42").assertIsDisplayed()
        composeTestRule.onNodeWithText("11.35").assertIsDisplayed()

        // Tapping the header reports the toggle and the chevron flips to "collapse"
        composeTestRule.onNodeWithContentDescription("Expand session").performClick()
        composeTestRule.onNodeWithContentDescription("Collapse session").assertIsDisplayed()
    }

    @Test
    fun testSessionCardActions_invokeExportAndDelete() {
        var exportSessionClicked = false
        var deleteSessionClicked = false

        composeTestRule.setContent {
            MaterialTheme {
                SessionCardActions(
                    onExport = { exportSessionClicked = true },
                    onDelete = { deleteSessionClicked = true }
                )
            }
        }

        // Export session
        composeTestRule.onNodeWithText("Export").performClick()
        assertTrue(exportSessionClicked)

        // Delete session
        composeTestRule.onNodeWithText("Delete").performClick()
        assertTrue(deleteSessionClicked)
    }

    @Test
    fun testSessionCardSolveRow_expanded_rendersInnerSolves() {
        val session = createSession(name = "Fast Solves", event = Mode.CUBE_3x3)
        val solve1 = createSolve(timeInMillis = 9500L, sessionId = session.id)
        val solve2 = createSolve(timeInMillis = 10200L, sessionId = session.id)

        val group = SessionGroupUiModel(
            session = session,
            solveCount = 2,
            // No header stats, so "9.50" only matches the solve row
            bestDurationMs = null,
            avgDurationMs = null,
            isExpanded = true,
            solves = listOf(solve1, solve2)
        )

        var clickedSolve: SolveTime? = null

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    group.solves.forEachIndexed { index, solve ->
                        SessionCardSolveRow(
                            sessionGroup = group,
                            solve = solve,
                            index = index,
                            isSelectionMode = false,
                            selectedSolveIds = emptySet(),
                            onSolveClick = { clicked, _ -> clickedSolve = clicked },
                            onSolveLongClick = {},
                            onToggleSolveSelection = {},
                            onTogglePlusTwo = {},
                            onToggleDnf = {},
                            onDeleteSolve = {}
                        )
                    }
                }
            }
        }

        // Solves are displayed
        composeTestRule.onNodeWithText("9.50").assertIsDisplayed()
        composeTestRule.onNodeWithText("10.20").assertIsDisplayed()

        // Clicking solve opens detail
        composeTestRule.onNodeWithText("9.50").performClick()
        assertEquals(solve1.id, clickedSolve?.id)
    }

    @Test
    fun testSessionCardSolveRow_selectionMode_togglesSelection() {
        val session = createSession()
        val solve1 = createSolve(timeInMillis = 8000L, sessionId = session.id)
        val solve2 = createSolve(timeInMillis = 9000L, sessionId = session.id)

        val group = SessionGroupUiModel(
            session = session,
            solveCount = 2,
            bestDurationMs = 8000L,
            avgDurationMs = 8500L,
            isExpanded = true,
            solves = listOf(solve1, solve2)
        )

        var toggledSolveId: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    group.solves.forEachIndexed { index, solve ->
                        SessionCardSolveRow(
                            sessionGroup = group,
                            solve = solve,
                            index = index,
                            isSelectionMode = true,
                            selectedSolveIds = setOf(solve1.id),
                            onSolveClick = { _, _ -> },
                            onSolveLongClick = {},
                            onToggleSolveSelection = { id -> toggledSolveId = id },
                            onTogglePlusTwo = {},
                            onToggleDnf = {},
                            onDeleteSolve = {}
                        )
                    }
                }
            }
        }

        // Tapping solve2 in selection mode triggers selection toggle
        composeTestRule.onNodeWithText("9.00").performClick()
        assertEquals(solve2.id, toggledSolveId)
    }

    // --- 3. HistoryFilterSortBottomSheet Tests ---

    @Test
    fun testHistoryFilterSortBottomSheet_rendersTabsAndCounters() {
        val state = HistoryUiState(
            isFilterSheetOpen = true,
            activeFilterSheetTab = 0,
            sessionSort = SessionSortOrder.MOST_RECENT,
            puzzleScope = PuzzleScope.ACTIVE_PUZZLE,
            solveSort = SolveSortOrder.MOST_RECENT,
            penaltyFilter = PenaltyFilter.ALL,
            timeRangeFilter = TimeRangeFilter(),
            dateRangeFilter = DateRangeFilter()
        )

        var resetAllClicked = false
        var selectedTab: Int? = null

        composeTestRule.setContent {
            MaterialTheme {
                HistoryFilterSortBottomSheet(
                    uiState = state,
                    onDismissRequest = {},
                    onSelectTab = { tab -> selectedTab = tab },
                    onSessionSortChange = {},
                    onPuzzleScopeChange = {},
                    onSolveSortChange = {},
                    onPenaltyFilterChange = {},
                    onTimeRangeFilterChange = {},
                    onDateRangeFilterChange = {},
                    onResetAll = { resetAllClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Filter & Sort").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Solves").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sort by").assertIsDisplayed()
        composeTestRule.onNodeWithText("Puzzle").performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Session Type").assertCountEquals(0)

        // Tab selection
        composeTestRule.onNodeWithText("Solves").performClick()
        assertEquals(1, selectedTab)
    }

    @Test
    fun testHistoryFilterSortBottomSheet_sessionChipCallbacks() {
        val state = HistoryUiState(
            isFilterSheetOpen = true,
            activeFilterSheetTab = 0
        )

        var changedSort: SessionSortOrder? = null
        var changedScope: PuzzleScope? = null

        composeTestRule.setContent {
            MaterialTheme {
                HistoryFilterSortBottomSheet(
                    uiState = state,
                    onDismissRequest = {},
                    onSelectTab = {},
                    onSessionSortChange = { changedSort = it },
                    onPuzzleScopeChange = { changedScope = it },
                    onSolveSortChange = {},
                    onPenaltyFilterChange = {},
                    onTimeRangeFilterChange = {},
                    onDateRangeFilterChange = {},
                    onResetAll = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Name (A-Z)").performClick()
        assertEquals(SessionSortOrder.NAME_ASC, changedSort)

        composeTestRule.onNodeWithText("All Puzzles").performScrollTo().performClick()
        assertEquals(PuzzleScope.ALL_PUZZLES, changedScope)
    }

    @Test
    fun testHistoryFilterSortBottomSheet_solvesTabChips() {
        val state = HistoryUiState(
            isFilterSheetOpen = true,
            activeFilterSheetTab = 1
        )

        var changedSort: SolveSortOrder? = null
        var changedPenalty: PenaltyFilter? = null
        var changedDatePreset: DatePreset? = null

        composeTestRule.setContent {
            MaterialTheme {
                HistoryFilterSortBottomSheet(
                    uiState = state,
                    onDismissRequest = {},
                    onSelectTab = {},
                    onSessionSortChange = {},
                    onPuzzleScopeChange = {},
                    onSolveSortChange = { changedSort = it },
                    onPenaltyFilterChange = { changedPenalty = it },
                    onTimeRangeFilterChange = {},
                    onDateRangeFilterChange = { changedDatePreset = it.preset },
                    onResetAll = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Fastest").performClick()
        assertEquals(SolveSortOrder.LOWEST_TIME, changedSort)

        composeTestRule.onNodeWithText("DNF").performScrollTo().performClick()
        assertEquals(PenaltyFilter.DNF_ONLY, changedPenalty)

        composeTestRule.onNodeWithText("Last 7 Days").performScrollTo().performClick()
        assertEquals(DatePreset.LAST_7_DAYS, changedDatePreset)
    }

    // --- 4. HistorySolveCard Long-Click and Selection Tests ---

    @Test
    fun testHistorySolveCard_longClick_triggersOnLongClick() {
        var longClicked = false
        val solve = createSolve(timeInMillis = 12500L)

        composeTestRule.setContent {
            MaterialTheme {
                HistorySolveCard(
                    solve = solve,
                    solveNumber = 1,
                    onClick = {},
                    onDelete = {},
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onLongClick = { longClicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("12.50").performTouchInput {
            longClick()
        }
        assertTrue("Long clicking solve card should invoke onLongClick", longClicked)
    }
}
