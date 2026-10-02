package com.maciekhetman.cubetimer.ui.dialogs

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.viewmodel.DatePreset
import com.maciekhetman.cubetimer.viewmodel.DateRangeFilter
import com.maciekhetman.cubetimer.viewmodel.HistoryUiState
import com.maciekhetman.cubetimer.viewmodel.PenaltyFilter
import com.maciekhetman.cubetimer.viewmodel.PuzzleScope
import com.maciekhetman.cubetimer.viewmodel.SessionSortOrder
import com.maciekhetman.cubetimer.viewmodel.SolveSortOrder
import com.maciekhetman.cubetimer.viewmodel.TimeRangeFilter
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * Filter & sort sheet for the History screen, split into a Sessions and a Solves page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryFilterSortBottomSheet(
    uiState: HistoryUiState,
    onDismissRequest: () -> Unit,
    onSelectTab: (Int) -> Unit,
    onSessionSortChange: (SessionSortOrder) -> Unit,
    onPuzzleScopeChange: (PuzzleScope) -> Unit,
    onSolveSortChange: (SolveSortOrder) -> Unit,
    onPenaltyFilterChange: (PenaltyFilter) -> Unit,
    onTimeRangeFilterChange: (TimeRangeFilter) -> Unit,
    onDateRangeFilterChange: (DateRangeFilter) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.history_filter_sort),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onResetAll,
                    enabled = uiState.totalActiveFilterCount > 0
                ) {
                    Text(stringResource(R.string.filters_reset_all))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    stringResource(R.string.filters_tab_sessions) to uiState.activeSessionFilterCount,
                    stringResource(R.string.filters_tab_solves) to uiState.activeSolveFilterCount
                ).forEachIndexed { index, (label, activeCount) ->
                    SegmentedButton(
                        selected = uiState.activeFilterSheetTab == index,
                        onClick = { onSelectTab(index) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        icon = {}
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(label)
                            if (activeCount > 0) Badge { Text("$activeCount") }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                if (uiState.activeFilterSheetTab == 0) {
                    FilterGroup(stringResource(R.string.filters_sort_by)) {
                        ChoiceChips(
                            options = SessionSortOrder.entries,
                            selected = uiState.sessionSort,
                            label = { stringResource(it.labelRes) },
                            onSelect = onSessionSortChange
                        )
                    }
                    FilterGroup(stringResource(R.string.filters_puzzle)) {
                        ChoiceChips(
                            options = PuzzleScope.entries,
                            selected = uiState.puzzleScope,
                            label = { stringResource(it.labelRes) },
                            onSelect = onPuzzleScopeChange
                        )
                    }
                } else {
                    FilterGroup(stringResource(R.string.filters_sort_by)) {
                        ChoiceChips(
                            options = SolveSortOrder.entries,
                            selected = uiState.solveSort,
                            label = { stringResource(it.labelRes) },
                            onSelect = onSolveSortChange
                        )
                    }
                    FilterGroup(stringResource(R.string.filters_penalty)) {
                        ChoiceChips(
                            options = PenaltyFilter.entries,
                            selected = uiState.penaltyFilter,
                            label = { stringResource(it.labelRes) },
                            onSelect = onPenaltyFilterChange
                        )
                    }
                    FilterGroup(stringResource(R.string.filters_time)) {
                        DurationRangeFields(
                            filter = uiState.timeRangeFilter,
                            onChange = onTimeRangeFilterChange
                        )
                    }
                    FilterGroup(stringResource(R.string.filters_date)) {
                        DateRangeChips(
                            filter = uiState.dateRangeFilter,
                            onChange = onDateRangeFilterChange
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    content()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            ChoiceChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}

@Composable
private fun DurationRangeFields(
    filter: TimeRangeFilter,
    onChange: (TimeRangeFilter) -> Unit
) {
    // Text is owned locally so partially typed input ("12.") isn't reformatted mid-edit.
    var minText by rememberSaveable { mutableStateOf(filter.minDurationMs.toSecondsText()) }
    var maxText by rememberSaveable { mutableStateOf(filter.maxDurationMs.toSecondsText()) }

    // Clear the fields when the filter is reset from outside (e.g. "Reset all").
    LaunchedEffect(filter.isActive) {
        if (!filter.isActive) {
            minText = ""
            maxText = ""
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = minText,
            onValueChange = {
                minText = it
                onChange(filter.copy(minDurationMs = it.parseSecondsToMillis()))
            },
            label = { Text(stringResource(R.string.filters_min)) },
            suffix = { Text(stringResource(R.string.filters_seconds_suffix)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = maxText,
            onValueChange = {
                maxText = it
                onChange(filter.copy(maxDurationMs = it.parseSecondsToMillis()))
            },
            label = { Text(stringResource(R.string.filters_max)) },
            suffix = { Text(stringResource(R.string.filters_seconds_suffix)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeChips(
    filter: DateRangeFilter,
    onChange: (DateRangeFilter) -> Unit
) {
    var showCustomPicker by rememberSaveable { mutableStateOf(false) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DatePreset.entries.forEach { preset ->
            val label = if (preset == DatePreset.CUSTOM) {
                filter.customRangeLabel() ?: stringResource(preset.labelRes)
            } else {
                stringResource(preset.labelRes)
            }
            ChoiceChip(
                label = label,
                selected = filter.preset == preset,
                onClick = {
                    if (preset == DatePreset.CUSTOM) {
                        showCustomPicker = true
                    } else {
                        onChange(DateRangeFilter(preset))
                    }
                }
            )
        }
    }

    if (showCustomPicker) {
        CustomDateRangeDialog(
            initial = filter,
            onConfirm = { start, end -> onChange(DateRangeFilter(DatePreset.CUSTOM, start, end)) },
            onDismiss = { showCustomPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    initial: DateRangeFilter,
    onConfirm: (startEpochMs: Long, endEpochMs: Long) -> Unit,
    onDismiss: () -> Unit
) {
    // The picker works in UTC-midnight millis; the filter stores local start/end-of-day millis.
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.customStartEpoch?.let { convertDay(it, TimeZone.getDefault(), UTC) },
        initialSelectedEndDateMillis = initial.customEndEpoch?.let { convertDay(it, TimeZone.getDefault(), UTC) }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    val end = state.selectedEndDateMillis ?: start
                    val local = TimeZone.getDefault()
                    onConfirm(
                        convertDay(start, UTC, local),
                        convertDay(end, UTC, local, dayOffset = 1) - 1
                    )
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/** Start of the calendar day containing [epochMs] in [from], plus [dayOffset] days, expressed in [to]. */
private fun convertDay(epochMs: Long, from: TimeZone, to: TimeZone, dayOffset: Int = 0): Long {
    val source = Calendar.getInstance(from).apply { timeInMillis = epochMs }
    return Calendar.getInstance(to).apply {
        clear()
        set(source.get(Calendar.YEAR), source.get(Calendar.MONTH), source.get(Calendar.DAY_OF_MONTH))
        add(Calendar.DAY_OF_MONTH, dayOffset)
    }.timeInMillis
}

private fun DateRangeFilter.customRangeLabel(): String? {
    if (preset != DatePreset.CUSTOM) return null
    val locale = Locale.getDefault()
    val format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    val start = customStartEpoch?.let { format.format(it) }
    val end = customEndEpoch?.let { format.format(it) }
    return when {
        start != null && end != null && start != end -> "$start – $end"
        start != null -> start
        end != null -> end
        else -> null
    }
}

private fun Long?.toSecondsText(): String =
    this?.let { BigDecimal.valueOf(it).movePointLeft(3).stripTrailingZeros().toPlainString() } ?: ""

private fun String.parseSecondsToMillis(): Long? =
    replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).roundToLong() }
