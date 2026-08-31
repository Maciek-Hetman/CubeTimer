package com.maciekhetman.cubetimer.model.admin

import androidx.compose.ui.graphics.Color

/**
 * Filter time ranges supported by the Admin Metrics Dashboard.
 */
enum class AdminTimeRange(
    val id: String,
    val label: String,
    val interval: String,
    val durationHours: Long
) {
    HOURS_24("24h", "24 Hours", "hour", 24L),
    DAYS_7("7d", "7 Days", "day", 7L * 24L),
    DAYS_30("30d", "30 Days", "day", 30L * 24L);

    companion object {
        fun fromId(id: String?): AdminTimeRange =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: HOURS_24
    }
}

/**
 * High-level overview statistics with computed performance and engagement ratios.
 */
data class AdminOverview(
    val totalUsers: Long,
    val verifiedUsers: Long,
    val newUsers24h: Long,
    val newUsers7d: Long,
    val newUsers30d: Long,
    val activeUsers24h: Long,
    val activeUsers7d: Long,
    val activeUsers30d: Long,
    val totalDevices: Long,
    val totalSessions: Long,
    val totalSolves: Long
) {
    val verificationRate: Double
        get() = if (totalUsers > 0) (verifiedUsers.toDouble() / totalUsers.toDouble()) * 100.0 else 0.0

    val activeRatio30d: Double
        get() = if (totalUsers > 0) (activeUsers30d.toDouble() / totalUsers.toDouble()) * 100.0 else 0.0

    val dauMauRatio: Double
        get() = if (activeUsers30d > 0) (activeUsers24h.toDouble() / activeUsers30d.toDouble()) * 100.0 else 0.0

    val avgSolvesPerSession: Double
        get() = if (totalSessions > 0) totalSolves.toDouble() / totalSessions.toDouble() else 0.0

    val avgDevicesPerUser: Double
        get() = if (totalUsers > 0) totalDevices.toDouble() / totalUsers.toDouble() else 0.0
}

/**
 * Single time bucket data point for traffic volume, throughput, and status code distributions.
 */
data class AdminTrafficPoint(
    val bucket: String,
    val requestCount: Long,
    val status2xx: Long,
    val status3xx: Long,
    val status4xx: Long,
    val status5xx: Long,
    val averageDurationMs: Double,
    val maxDurationMs: Long,
    val throughputRpm: Double,
    val successRate: Double,
    val errorRate: Double
)

/**
 * Categorization of request endpoints into visual functional groups.
 */
enum class RequestTypeCategory(val key: String, val label: String, val color: Color) {
    AUTH("auth", "Auth", Color(0xFF6750A4)),
    ACCOUNT("account", "Account", Color(0xFF388E3C)),
    SYNC("sync", "Sync", Color(0xFFF57C00)),
    SNAPSHOT("snapshot", "Snapshot", Color(0xFFD32F2F)),
    SESSIONS("sessions", "Sessions", Color(0xFF7E57C2)),
    STATS("stats", "Stats", Color(0xFFFFA000)),
    OTHER("other", "Other", Color(0xFF757575));

    companion object {
        fun fromKey(key: String): RequestTypeCategory =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Aggregated count and proportional share for a request category.
 */
data class AdminRequestTypeItem(
    val category: RequestTypeCategory,
    val requestCount: Long,
    val sharePercentage: Double
)

/**
 * Complete traffic statistics including time series points and category breakdowns.
 */
data class AdminTrafficData(
    val from: String,
    val to: String,
    val interval: String,
    val points: List<AdminTrafficPoint>,
    val types: List<AdminRequestTypeItem>,
    val totalRequests: Long,
    val totalSuccess: Long,
    val totalErrors: Long,
    val overallAvgLatencyMs: Double
)

/**
 * Structured server application error log entry.
 */
data class AdminErrorLogItem(
    val id: Long,
    val createdAt: String,
    val userId: String?,
    val method: String,
    val route: String,
    val status: Int,
    val code: String,
    val message: String
)

/**
 * Paginated container for error logs.
 */
data class AdminErrorLogPage(
    val errors: List<AdminErrorLogItem>,
    val nextCursor: String?
)
