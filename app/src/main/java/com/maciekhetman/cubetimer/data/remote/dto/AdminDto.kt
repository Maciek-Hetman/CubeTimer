package com.maciekhetman.cubetimer.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Overview KPIs returned by GET /v1/admin/stats/overview
 */
@Serializable
data class AdminOverviewDto(
    @SerialName("total_users") val totalUsers: Long = 0L,
    @SerialName("verified_users") val verifiedUsers: Long = 0L,
    @SerialName("new_users_24h") val newUsers24h: Long = 0L,
    @SerialName("new_users_7d") val newUsers7d: Long = 0L,
    @SerialName("new_users_30d") val newUsers30d: Long = 0L,
    @SerialName("active_users_24h") val activeUsers24h: Long = 0L,
    @SerialName("active_users_7d") val activeUsers7d: Long = 0L,
    @SerialName("active_users_30d") val activeUsers30d: Long = 0L,
    @SerialName("total_devices") val totalDevices: Long = 0L,
    @SerialName("total_sessions") val totalSessions: Long = 0L,
    @SerialName("total_solves") val totalSolves: Long = 0L
)

/**
 * Time-bucketed request traffic stats returned by GET /v1/admin/stats/requests
 */
@Serializable
data class AdminRequestStatsDto(
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("interval") val interval: String = "hour",
    @SerialName("points") val points: List<AdminRequestStatsPointDto> = emptyList()
)

/**
 * Single data point in time series request stats.
 */
@Serializable
data class AdminRequestStatsPointDto(
    @SerialName("bucket") val bucket: String,
    @SerialName("request_count") val requestCount: Long = 0L,
    @SerialName("status_2xx") val status2xx: Long = 0L,
    @SerialName("status_3xx") val status3xx: Long = 0L,
    @SerialName("status_4xx") val status4xx: Long = 0L,
    @SerialName("status_5xx") val status5xx: Long = 0L,
    @SerialName("average_duration_ms") val averageDurationMs: Double = 0.0,
    @SerialName("max_duration_ms") val maxDurationMs: Long = 0L
)

/**
 * Request counts grouped by category returned by GET /v1/admin/stats/request-types
 */
@Serializable
data class AdminRequestTypeStatsDto(
    @SerialName("from") val from: String? = null,
    @SerialName("to") val to: String? = null,
    @SerialName("interval") val interval: String = "hour",
    @SerialName("types") val types: List<AdminRequestTypeCountDto> = emptyList()
)

/**
 * Single request category item with total request count.
 */
@Serializable
data class AdminRequestTypeCountDto(
    @SerialName("type") val type: String,
    @SerialName("request_count") val requestCount: Long = 0L
)

/**
 * Single error log entry returned in GET /v1/admin/stats/errors
 */
@Serializable
data class ErrorLogDto(
    @SerialName("id") val id: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("method") val method: String,
    @SerialName("route") val route: String,
    @SerialName("status") val status: Int,
    @SerialName("code") val code: String,
    @SerialName("message") val message: String
)

/**
 * Paginated error logs response returned by GET /v1/admin/stats/errors
 */
@Serializable
data class ErrorLogResponseDto(
    @SerialName("errors") val errors: List<ErrorLogDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null
)
