package com.maciekhetman.cubetimer.data.admin

import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.dto.AdminOverviewDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestStatsDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestStatsPointDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestTypeCountDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestTypeStatsDto
import com.maciekhetman.cubetimer.data.remote.dto.ErrorLogDto
import com.maciekhetman.cubetimer.data.remote.dto.ErrorLogResponseDto
import com.maciekhetman.cubetimer.model.admin.AdminTimeRange
import com.maciekhetman.cubetimer.model.admin.RequestTypeCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class AdminRepositoryTest {

    private lateinit var fakeApiClient: FakeCubeSyncApiClient
    private lateinit var repository: AdminRepositoryImpl

    @Before
    fun setup() {
        fakeApiClient = FakeCubeSyncApiClient()
        repository = AdminRepositoryImpl(apiClient = fakeApiClient)
    }

    @Test
    fun testGetOverviewMapsDtoCorrectly() = runTest {
        val dto = AdminOverviewDto(
            totalUsers = 200,
            verifiedUsers = 170,
            newUsers24h = 10,
            newUsers7d = 30,
            newUsers30d = 60,
            activeUsers24h = 80,
            activeUsers7d = 140,
            activeUsers30d = 180,
            totalDevices = 250,
            totalSessions = 600,
            totalSolves = 12000
        )
        fakeApiClient.overviewDto = dto

        val result = repository.getOverview().getOrThrow()

        assertEquals(200, result.totalUsers)
        assertEquals(170, result.verifiedUsers)
        assertEquals(250, result.totalDevices)
        assertEquals(600, result.totalSessions)
        assertEquals(12000, result.totalSolves)
        assertEquals(80, result.activeUsers24h)
        assertEquals(140, result.activeUsers7d)
        assertEquals(180, result.activeUsers30d)
        assertEquals(10, result.newUsers24h)
        assertEquals(30, result.newUsers7d)
        assertEquals(60, result.newUsers30d)

        // Verify computed metrics
        assertEquals(85.0, result.verificationRate, 0.01) // 170/200
        assertEquals(90.0, result.activeRatio30d, 0.01)   // 180/200
        assertEquals(44.44, result.dauMauRatio, 0.01)     // 80/180
        assertEquals(20.0, result.avgSolvesPerSession, 0.01) // 12000/600
        assertEquals(1.25, result.avgDevicesPerUser, 0.01) // 250/200
    }

    @Test
    fun testGetTrafficAggregatesPointsAndComputesLatency() = runTest {
        val statsDto = AdminRequestStatsDto(
            from = "2026-08-29T10:00:00Z",
            to = "2026-08-30T10:00:00Z",
            interval = "hour",
            points = listOf(
                AdminRequestStatsPointDto(
                    bucket = "2026-08-30T10:00:00Z",
                    requestCount = 100,
                    status2xx = 90,
                    status3xx = 0,
                    status4xx = 8,
                    status5xx = 2,
                    averageDurationMs = 50.0,
                    maxDurationMs = 200
                ),
                AdminRequestStatsPointDto(
                    bucket = "2026-08-30T11:00:00Z",
                    requestCount = 200,
                    status2xx = 195,
                    status3xx = 0,
                    status4xx = 5,
                    status5xx = 0,
                    averageDurationMs = 40.0,
                    maxDurationMs = 150
                )
            )
        )
        val typeStatsDto = AdminRequestTypeStatsDto(
            from = "2026-08-29T10:00:00Z",
            to = "2026-08-30T10:00:00Z",
            interval = "hour",
            types = listOf(
                AdminRequestTypeCountDto(type = "sync", requestCount = 200),
                AdminRequestTypeCountDto(type = "auth", requestCount = 100)
            )
        )

        fakeApiClient.requestStatsDto = statsDto
        fakeApiClient.requestTypeStatsDto = typeStatsDto

        val fixedNow = Instant.parse("2026-08-30T10:00:00Z")
        val traffic = repository.getTrafficData(AdminTimeRange.HOURS_24, fixedNow).getOrThrow()

        assertEquals(300, traffic.totalRequests)
        assertEquals(285, traffic.totalSuccess) // 90 + 195
        assertEquals(15, traffic.totalErrors)   // 8 + 2 + 5 + 0
        assertEquals(45.0, traffic.overallAvgLatencyMs, 0.01)
        assertEquals(2, traffic.points.size)
        assertEquals(2, traffic.types.size)
    }

    @Test
    fun testGetErrorLogsMapsPagination() = runTest {
        val errorLogsDto = ErrorLogResponseDto(
            errors = listOf(
                ErrorLogDto(
                    id = 10,
                    createdAt = "2026-08-30T10:00:00Z",
                    method = "POST",
                    route = "/v1/sync",
                    status = 409,
                    code = "cursor_expired",
                    message = "Sync cursor expired",
                    userId = "usr_123"
                )
            ),
            nextCursor = "cursor_token_abc"
        )
        fakeApiClient.errorLogResponseDto = errorLogsDto

        val page = repository.getErrorLogs(before = "cursor_previous").getOrThrow()

        assertEquals(1, page.errors.size)
        assertEquals(10, page.errors.first().id)
        assertEquals("POST", page.errors.first().method)
        assertEquals("/v1/sync", page.errors.first().route)
        assertEquals(409, page.errors.first().status)
        assertEquals("cursor_expired", page.errors.first().code)
        assertEquals("usr_123", page.errors.first().userId)
        assertEquals("cursor_token_abc", page.nextCursor)
    }

    private class FakeCubeSyncApiClient : CubeSyncApiClient {
        var overviewDto = AdminOverviewDto()
        var requestStatsDto = AdminRequestStatsDto()
        var requestTypeStatsDto = AdminRequestTypeStatsDto()
        var errorLogResponseDto = ErrorLogResponseDto()

        override suspend fun getAdminOverview(authToken: String?): AdminOverviewDto = overviewDto

        override suspend fun getAdminRequestStats(
            from: String?,
            to: String?,
            interval: String?,
            authToken: String?
        ): AdminRequestStatsDto = requestStatsDto

        override suspend fun getAdminRequestTypeStats(
            from: String?,
            to: String?,
            interval: String?,
            authToken: String?
        ): AdminRequestTypeStatsDto = requestTypeStatsDto

        override suspend fun getAdminErrorLogs(
            before: String?,
            authToken: String?
        ): ErrorLogResponseDto = errorLogResponseDto

        override suspend fun register(request: com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest) = throw NotImplementedError()
        override suspend fun resendVerificationEmail(email: String) = throw NotImplementedError()
        override suspend fun verifyEmail(token: String) = throw NotImplementedError()
        override suspend fun login(request: com.maciekhetman.cubetimer.data.remote.dto.LoginRequest) = throw NotImplementedError()
        override suspend fun refreshToken(refreshToken: String) = throw NotImplementedError()
        override suspend fun logout(refreshToken: String) = throw NotImplementedError()
        override suspend fun requestPasswordReset(email: String) = throw NotImplementedError()
        override suspend fun confirmPasswordReset(token: String, newPassword: String) = throw NotImplementedError()
        override suspend fun loginWithGoogle(request: com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest) = throw NotImplementedError()
        override suspend fun linkGoogle(idToken: String, authToken: String?) = throw NotImplementedError()
        override suspend fun getCurrentUser(authToken: String?) = throw NotImplementedError()
        override suspend fun changePassword(request: com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest, authToken: String?) = throw NotImplementedError()
        override suspend fun deleteAccount(authToken: String?) = throw NotImplementedError()
        override suspend fun sync(request: com.maciekhetman.cubetimer.data.remote.dto.SyncRequest, authToken: String?) = throw NotImplementedError()
        override suspend fun snapshot(request: com.maciekhetman.cubetimer.data.remote.dto.SnapshotRequest, authToken: String?) = throw NotImplementedError()
    }
}
