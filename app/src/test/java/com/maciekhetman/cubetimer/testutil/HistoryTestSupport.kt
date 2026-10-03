package com.maciekhetman.cubetimer.testutil

import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.viewmodel.HistoryUiState
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * [HistoryViewModel.uiState] only runs while something collects it (like HistoryScreen does), so a
 * test that reads `uiState.value` needs a subscriber for the lifetime of the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepUiStateActive(viewModel: HistoryViewModel): HistoryViewModel {
    backgroundScope.launch { viewModel.uiState.collect {} }
    return viewModel
}

/** The selected solves that are on screen: those of the expanded (and filtered) session groups. */
fun HistoryUiState.selectedSolves(): List<SolveTime> =
    sessionGroups.flatMap { it.solves }.filter { it.id in selectedSolveIds }
