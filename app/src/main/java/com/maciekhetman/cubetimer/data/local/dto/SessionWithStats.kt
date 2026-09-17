package com.maciekhetman.cubetimer.data.local.dto

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity

/**
 * Data transfer object representing a speedcubing session aggregated with its solve statistics.
 *
 * Used by the History screen and Session management to display session headers with
 * solve counts, personal best single duration, and average solve duration without
 * loading all individual solve entities into memory upfront.
 */
data class SessionWithStats(
    @Embedded
    val session: SessionEntity,

    @ColumnInfo(name = "solve_count")
    val solveCount: Int,

    @ColumnInfo(name = "best_duration_ms")
    val bestDurationMs: Long?,

    @ColumnInfo(name = "avg_duration_ms")
    val avgDurationMs: Long?
)
