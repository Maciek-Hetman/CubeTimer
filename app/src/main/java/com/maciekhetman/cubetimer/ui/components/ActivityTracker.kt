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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maciekhetman.cubetimer.model.SolveTime
import java.util.*

private data class ActivityTile(
    val isFuture: Boolean,
    val level: Int,
    val solvesCount: Int
)

private data class ActivityData(
    val weeksList: List<List<ActivityTile>>,
    val maxSolvesPerDay: Int
)

@Composable
fun ActivityTracker(
    solves: List<SolveTime>,
    modifier: Modifier = Modifier
) {
    val weeks = 12 // Show last 12 weeks
    
    val activityData = remember(solves) {
        val calendar = Calendar.getInstance()
        
        // Group solves by date (ignoring time)
        val solvesByDate = solves.groupBy { solve ->
            calendar.timeInMillis = solve.timestamp
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }
        
        val maxSolves = solvesByDate.values.maxOfOrNull { it.size } ?: 1
        
        // Pre-generate grid cells
        val startCalendar = Calendar.getInstance()
        startCalendar.add(Calendar.WEEK_OF_YEAR, -weeks + 1)
        startCalendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        startCalendar.set(Calendar.HOUR_OF_DAY, 0)
        startCalendar.set(Calendar.MINUTE, 0)
        startCalendar.set(Calendar.SECOND, 0)
        startCalendar.set(Calendar.MILLISECOND, 0)
        
        val today = Calendar.getInstance()
        
        val weeksList = List(weeks) { weekIndex ->
            List(7) { dayIndex ->
                // Calculate date for this cell
                val cellCalendar = startCalendar.clone() as Calendar
                cellCalendar.add(Calendar.WEEK_OF_YEAR, weekIndex)
                cellCalendar.add(Calendar.DAY_OF_WEEK, dayIndex)
                
                val dateMillis = cellCalendar.timeInMillis
                val isFuture = cellCalendar.after(today)
                val solvesCount = solvesByDate[dateMillis]?.size ?: 0
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
        ActivityData(weeksList = weeksList, maxSolvesPerDay = maxSolves)
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Title and Legend
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Activity",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            
            // Legend
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Less",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                repeat(5) { level ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = getActivityColor(level, 4, MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(3.dp)
                            )
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
                Text(
                    text = "More",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        
        // Grid of days - spans full width
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start)
        ) {
            // Show day labels on the left
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 12.dp)
            ) {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                                        color = getActivityColor(tile.level, activityData.maxSolvesPerDay, MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(3.dp)
                                    )
                                    .border(
                                        width = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
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
private fun getActivityColor(level: Int, maxSolves: Int, baseColor: Color): Color {
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        1 -> baseColor.copy(alpha = 0.2f)
        2 -> baseColor.copy(alpha = 0.4f)
        3 -> baseColor.copy(alpha = 0.6f)
        4 -> baseColor.copy(alpha = 0.9f)
        else -> baseColor
    }
}
