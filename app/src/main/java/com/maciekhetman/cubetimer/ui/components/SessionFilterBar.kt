package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.StatsFilter

/**
 * Reusable horizontal filter chips bar for switching between Active Session,
 * All Solves, and a Specific historical Session.
 *
 * Adheres to Material 3 Expressive chip specifications (16dp rounded corners,
 * labelMedium typography, 16dp checkmark icons).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionFilterBar(
    currentFilter: StatsFilter,
    onFilterSelected: (StatsFilter) -> Unit,
    activeSession: Session? = null,
    activeSessionSolvesCount: Int = 0,
    allSolvesCount: Int = 0,
    sessions: List<Session> = emptyList(),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = currentFilter is StatsFilter.ActiveSession,
            onClick = { onFilterSelected(StatsFilter.ActiveSession) },
            shape = RoundedCornerShape(16.dp),
            label = {
                Text(
                    text = "Active Session ($activeSessionSolvesCount)",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = if (currentFilter is StatsFilter.ActiveSession) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else null
        )

        FilterChip(
            selected = currentFilter is StatsFilter.AllSessions,
            onClick = { onFilterSelected(StatsFilter.AllSessions) },
            shape = RoundedCornerShape(16.dp),
            label = {
                Text(
                    text = "All Solves ($allSolvesCount)",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = if (currentFilter is StatsFilter.AllSessions) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else null
        )

        if (currentFilter is StatsFilter.SpecificSession) {
            FilterChip(
                selected = true,
                onClick = {},
                shape = RoundedCornerShape(16.dp),
                label = {
                    Text(
                        text = currentFilter.sessionName,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}
