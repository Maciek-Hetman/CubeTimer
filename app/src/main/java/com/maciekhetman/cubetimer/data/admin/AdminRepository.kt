package com.maciekhetman.cubetimer.data.admin

import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.model.admin.AdminErrorLogItem
import com.maciekhetman.cubetimer.model.admin.AdminErrorLogPage
import com.maciekhetman.cubetimer.model.admin.AdminOverview
import com.maciekhetman.cubetimer.model.admin.AdminRequestTypeItem
import com.maciekhetman.cubetimer.model.admin.AdminTimeRange
import com.maciekhetman.cubetimer.model.admin.AdminTrafficData
import com.maciekhetman.cubetimer.model.admin.AdminTrafficPoint
import com.maciekhetman.cubetimer.model.admin.RequestTypeCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Repository interface providing administrative metrics, time-series traffic stats, and error logs.
 */
interface AdminRepository {
    suspend fun getOverview(): Result<AdminOverview>
    suspend fun getTrafficData(range: AdminTimeRange, now: Instant = Instant.now()): Result<AdminTrafficData>
    suspend fun getErrorLogs(before: String? = null): Result<AdminErrorLogPage>
}

/**
 * Default implementation of [AdminRepository] backed by [CubeSyncApiClient].
 */
class AdminRepositoryImpl(
    private val apiClient: CubeSyncApiClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AdminRepository {

    override suspend fun getOverview(): Result<AdminOverview> = withContext(ioDispatcher) {
        runCatching {
            val dto = apiClient.getAdminOverview()
            AdminOverview(
                totalUsers = dto.totalUsers,
                verifiedUsers = dto.verifiedUsers,
                newUsers24h = dto.newUsers24h,
                newUsers7d = dto.newUsers7d,
                newUsers30d = dto.newUsers30d,
                activeUsers24h = dto.activeUsers24h,
                activeUsers7d = dto.activeUsers7d,
                activeUsers30d = dto.activeUsers30d,
                totalDevices = dto.totalDevices,
                totalSessions = dto.totalSessions,
                totalSolves = dto.totalSolves
            )
        }
    }

    override suspend fun getTrafficData(
        range: AdminTimeRange,
        now: Instant
    ): Result<AdminTrafficData> = withContext(ioDispatcher) {
        runCatching {
            val fromInstant = now.minus(range.durationHours, ChronoUnit.HOURS)
            val fromIso = fromInstant.toString()
            val toIso = now.toString()

            val requestsDto = apiClient.getAdminRequestStats(from = fromIso, to = toIso, interval = range.interval)
            val typesDto = apiClient.getAdminRequestTypeStats(from = fromIso, to = toIso, interval = range.interval)

            val minutesInBucket = if (range.interval.equals("day", ignoreCase = true)) 1440.0 else 60.0

            val points = requestsDto.points.map { point ->
                val count = point.requestCount
                val success = point.status2xx
                val errors = point.status4xx + point.status5xx
                AdminTrafficPoint(
                    bucket = point.bucket,
                    requestCount = count,
                    status2xx = success,
                    status3xx = point.status3xx,
                    status4xx = point.status4xx,
                    status5xx = point.status5xx,
                    averageDurationMs = point.averageDurationMs,
                    maxDurationMs = point.maxDurationMs,
                    throughputRpm = count / minutesInBucket,
                    successRate = if (count > 0) (success.toDouble() / count.toDouble()) * 100.0 else 0.0,
                    errorRate = if (count > 0) (errors.toDouble() / count.toDouble()) * 100.0 else 0.0
                )
            }

            val totalTypesCount = typesDto.types.sumOf { it.requestCount }
            val types = typesDto.types
                .sortedByDescending { it.requestCount }
                .map { typeDto ->
                    AdminRequestTypeItem(
                        category = RequestTypeCategory.fromKey(typeDto.type),
                        requestCount = typeDto.requestCount,
                        sharePercentage = if (totalTypesCount > 0) {
                            (typeDto.requestCount.toDouble() / totalTypesCount.toDouble()) * 100.0
                        } else {
                            0.0
                        }
                    )
                }

            val totalReq = points.sumOf { it.requestCount }
            val totalSucc = points.sumOf { it.status2xx }
            val totalErr = points.sumOf { it.status4xx + it.status5xx }
            val avgLatency = if (points.isNotEmpty()) points.map { it.averageDurationMs }.average() else 0.0

            AdminTrafficData(
                from = requestsDto.from ?: fromIso,
                to = requestsDto.to ?: toIso,
                interval = requestsDto.interval,
                points = points,
                types = types,
                totalRequests = totalReq,
                totalSuccess = totalSucc,
                totalErrors = totalErr,
                overallAvgLatencyMs = avgLatency
            )
        }
    }

    override suspend fun getErrorLogs(before: String?): Result<AdminErrorLogPage> = withContext(ioDispatcher) {
        runCatching {
            val response = apiClient.getAdminErrorLogs(before = before)
            val errors = response.errors.map { dto ->
                AdminErrorLogItem(
                    id = dto.id,
                    createdAt = dto.createdAt,
                    userId = dto.userId,
                    method = dto.method,
                    route = dto.route,
                    status = dto.status,
                    code = dto.code,
                    message = dto.message
                )
            }
            AdminErrorLogPage(
                errors = errors,
                nextCursor = response.nextCursor
            )
        }
    }
}
