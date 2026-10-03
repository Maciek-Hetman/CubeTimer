package com.maciekhetman.cubetimer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.model.SolveTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

private data class ActivityTile(
    val isFuture: Boolean,
    val level: Int,
    val solvesCount: Int
)

private data class ActivityData(
    val weeksList: List<List<ActivityTile>>
)

/**
 * First day of the [weeks]-week grid ending in the week of [today]. The grid is always
 * Sunday-first (its row labels are fixed Sunday..Saturday), whatever the locale's first day of the week is.
 */
internal fun activityGridStart(today: LocalDate, weeks: Int): LocalDate =
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(weeks - 1L)

@Composable
fun ActivityTracker(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    val weeks = 12 // Show last 12 weeks
    
    val activityData = remember(solves) {
        val zone = ZoneId.systemDefault()

        // Group solves by local date (ignoring time).
        val solvesByDate = solves.groupBy { solve ->
            Instant.ofEpochMilli(solve.timestamp)
                .atZone(zone)
                .toLocalDate()
        }
        
        val maxSolves = solvesByDate.values.maxOfOrNull { it.size } ?: 1
        
        // Pre-generate grid cells
        val today = LocalDate.now(zone)
        val start = activityGridStart(today, weeks)
        
        val weeksList = List(weeks) { weekIndex ->
            List(7) { dayIndex ->
                // Calculate date for this cell
                val date = start.plusDays(weekIndex * 7L + dayIndex)
                
                val isFuture = date.isAfter(today)
                val solvesCount = solvesByDate[date]?.size ?: 0
                val level = when {
                    solvesCount == 0 -> 0
                    solvesCount <= maxSolves / 4 -> 1
                    solvesCount <= maxSolves / 2 -> 2
                    solvesCount <= maxSolves * 3 / 4 -> 3
                    else -> 4
                }
                
                ActivityTile(
                    isFuture = isFuture,
                    level = level,
                    solvesCount = solvesCount
                )
            }
        }
        ActivityData(weeksList = weeksList)
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Title and Legend
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.activity_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            // Legend
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.activity_less),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                repeat(5) { level ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = getActivityColor(level, MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(3.dp)
                            )
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
                Text(
                    text = stringResource(R.string.activity_more),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // Grid of days - spans full width
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start)
        ) {
            // Show day labels on the left
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 12.dp)
            ) {
                val locale = LocalConfiguration.current.locales[0]
                listOf(
                    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
                ).map { it.getDisplayName(TextStyle.SHORT, locale) }.forEach { day ->
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .width(40.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = day,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Week columns
            activityData.weeksList.forEach { week ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    week.forEach { tile ->
                        // Don't show future tiles
                        if (tile.isFuture) {
                            Spacer(modifier = Modifier.size(20.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        color = getActivityColor(tile.level, MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(3.dp)
                                    )
                                    .border(
                                        width = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getActivityColor(level: Int, baseColor: Color): Color {
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        1 -> baseColor.copy(alpha = 0.25f)
        2 -> baseColor.copy(alpha = 0.45f)
        3 -> baseColor.copy(alpha = 0.7f)
        4 -> baseColor
        else -> baseColor
    }
}
