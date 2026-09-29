package com.maciekhetman.cubetimer.testutil

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
