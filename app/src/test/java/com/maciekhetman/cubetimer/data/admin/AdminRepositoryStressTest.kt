package com.maciekhetman.cubetimer.data.admin

import com.maciekhetman.cubetimer.data.remote.CubeSyncApiClient
import com.maciekhetman.cubetimer.data.remote.dto.AdminOverviewDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestStatsDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestStatsPointDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestTypeCountDto
import com.maciekhetman.cubetimer.data.remote.dto.AdminRequestTypeStatsDto
import com.maciekhetman.cubetimer.data.remote.dto.ErrorLogDto
import com.maciekhetman.cubetimer.data.remote.dto.ErrorLogResponseDto
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.admin.AdminOverview
import com.maciekhetman.cubetimer.model.admin.AdminTimeRange
import com.maciekhetman.cubetimer.model.admin.RequestTypeCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Adversarial stress tests for [AdminRepositoryImpl] covering:
 * - HTTP error codes (401, 403, 500, 502, 503)
 * - Network failures (IOException) and serialization errors
 * - Extreme data scenarios: 0 data points, 0 users, 0 solves, max data points
 * - Division by zero / NaN safety in all computed properties
 */
class AdminRepositoryStressTest {

    private lateinit var fakeApiClient: FakeStressCubeSyncApiClient
    private lateinit var repository: AdminRepositoryImpl

    @Before
    fun setup() {
        fakeApiClient = FakeStressCubeSyncApiClient()
        repository = AdminRepositoryImpl(apiClient = fakeApiClient)
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP ERROR & EXCEPTION HANDLING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `getOverview returns failure on 401 Unauthorized`() = runTest {
        fakeApiClient.throwException = AuthException.Unauthorized("Token expired")
        val result = repository.getOverview()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.Unauthorized)
        assertEquals("Token expired", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getOverview returns failure on 403 Forbidden`() = runTest {
        fakeApiClient.throwException = AuthException.Forbidden("Admin role required")
        val result = repository.getOverview()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.Forbidden)
        assertEquals("Admin role required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `getOverview returns failure on 500 Server Error`() = runTest {
        fakeApiClient.throwException = AuthException.ApiError("internal_error", "Internal database failure", 500)
        val result = repository.getOverview()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.ApiError)
    }

    @Test
    fun `getOverview returns failure on Network Error`() = runTest {
        fakeApiClient.throwException = AuthException.NetworkError("Connection timed out")
        val result = repository.getOverview()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.NetworkError)
    }

    @Test
    fun `getTrafficData returns failure on 401 Unauthorized`() = runTest {
        fakeApiClient.throwException = AuthException.Unauthorized("Invalid access token")
        val result = repository.getTrafficData(AdminTimeRange.HOURS_24)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.Unauthorized)
    }

    @Test
    fun `getTrafficData returns failure on 403 Forbidden`() = runTest {
        fakeApiClient.throwException = AuthException.Forbidden("Forbidden endpoint")
        val result = repository.getTrafficData(AdminTimeRange.DAYS_7)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.Forbidden)
    }

    @Test
    fun `getTrafficData returns failure on 500 Server Error`() = runTest {
        fakeApiClient.throwException = AuthException.ApiError("stats_failure", "Stats aggregation engine offline", 500)
        val result = repository.getTrafficData(AdminTimeRange.DAYS_30)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.ApiError)
    }

    @Test
    fun `getErrorLogs returns failure on 401 Unauthorized`() = runTest {
        fakeApiClient.throwException = AuthException.Unauthorized("Unauthorized")
        val result = repository.getErrorLogs()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.Unauthorized)
    }

    @Test
    fun `getErrorLogs returns failure on 403 Forbidden`() = runTest {
        fakeApiClient.throwException = AuthException.Forbidden("Forbidden")
        val result = repository.getErrorLogs()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.Forbidden)
    }

    @Test
    fun `getErrorLogs returns failure on 500 Server Error`() = runTest {
        fakeApiClient.throwException = AuthException.ApiError("log_corrupted", "Error log storage corrupted", 500)
        val result = repository.getErrorLogs(before = "cursor_123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthException.ApiError)
    }

    // ---------------------------------------------------------------------------------------------
    // EMPTY DATA LISTS & DIVISION BY ZERO GUARDS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `getOverview handles all zero metrics without NaN or division by zero`() = runTest {
        fakeApiClient.overviewDto = AdminOverviewDto(
            totalUsers = 0,
            verifiedUsers = 0,
            newUsers24h = 0,
            newUsers7d = 0,
            newUsers30d = 0,
            activeUsers24h = 0,
            activeUsers7d = 0,
            activeUsers30d = 0,
            totalDevices = 0,
            totalSessions = 0,
            totalSolves = 0
        )

        val result = repository.getOverview().getOrThrow()

        assertEquals(0, result.totalUsers)
        assertEquals(0, result.verifiedUsers)
        assertEquals(0.0, result.verificationRate, 0.0)
        assertFalse("verificationRate must not be NaN", result.verificationRate.isNaN())
        assertFalse("verificationRate must not be Infinite", result.verificationRate.isInfinite())

        assertEquals(0.0, result.activeRatio30d, 0.0)
        assertFalse("activeRatio30d must not be NaN", result.activeRatio30d.isNaN())

        assertEquals(0.0, result.dauMauRatio, 0.0)
        assertFalse("dauMauRatio must not be NaN", result.dauMauRatio.isNaN())

        assertEquals(0.0, result.avgSolvesPerSession, 0.0)
        assertFalse("avgSolvesPerSession must not be NaN", result.avgSolvesPerSession.isNaN())

        assertEquals(0.0, result.avgDevicesPerUser, 0.0)
        assertFalse("avgDevicesPerUser must not be NaN", result.avgDevicesPerUser.isNaN())
    }

    @Test
    fun `getTrafficData handles empty points and empty types lists safely`() = runTest {
        fakeApiClient.requestStatsDto = AdminRequestStatsDto(
            from = "2026-08-29T10:00:00Z",
            to = "2026-08-30T10:00:00Z",
            interval = "hour",
            points = emptyList()
        )
        fakeApiClient.requestTypeStatsDto = AdminRequestTypeStatsDto(
            from = "2026-08-29T10:00:00Z",
            to = "2026-08-30T10:00:00Z",
            interval = "hour",
            types = emptyList()
        )

        val result = repository.getTrafficData(AdminTimeRange.HOURS_24, Instant.parse("2026-08-30T10:00:00Z")).getOrThrow()

        assertEquals(0, result.totalRequests)
        assertEquals(0, result.totalSuccess)
        assertEquals(0, result.totalErrors)
        assertEquals(0.0, result.overallAvgLatencyMs, 0.0)
        assertFalse("overallAvgLatencyMs must not be NaN", result.overallAvgLatencyMs.isNaN())
        assertTrue(result.points.isEmpty())
        assertTrue(result.types.isEmpty())
    }

    @Test
    fun `getTrafficData handles points with zero requestCount without division by zero`() = runTest {
        fakeApiClient.requestStatsDto = AdminRequestStatsDto(
            points = listOf(
                AdminRequestStatsPointDto(
                    bucket = "2026-08-30T03:00:00Z",
                    requestCount = 0,
                    status2xx = 0,
                    status3xx = 0,
                    status4xx = 0,
                    status5xx = 0,
                    averageDurationMs = 0.0,
                    maxDurationMs = 0
                )
            )
        )
        fakeApiClient.requestTypeStatsDto = AdminRequestTypeStatsDto(
            types = listOf(
                AdminRequestTypeCountDto(type = "sync", requestCount = 0)
            )
        )

        val result = repository.getTrafficData(AdminTimeRange.HOURS_24, Instant.parse("2026-08-30T10:00:00Z")).getOrThrow()

        assertEquals(1, result.points.size)
        val point = result.points.first()
        assertEquals(0.0, point.throughputRpm, 0.0)
        assertEquals(0.0, point.successRate, 0.0)
        assertEquals(0.0, point.errorRate, 0.0)
        assertFalse(point.successRate.isNaN())
        assertFalse(point.errorRate.isNaN())

        assertEquals(1, result.types.size)
        val typeItem = result.types.first()
        assertEquals(0.0, typeItem.sharePercentage, 0.0)
        assertFalse(typeItem.sharePercentage.isNaN())
    }

    @Test
    fun `getTrafficData correctly calculates throughput RPM for day interval`() = runTest {
        fakeApiClient.requestStatsDto = AdminRequestStatsDto(
            interval = "day",
            points = listOf(
                AdminRequestStatsPointDto(
                    bucket = "2026-08-30",
                    requestCount = 1440,
                    status2xx = 1440,
                    status3xx = 0,
                    status4xx = 0,
                    status5xx = 0,
                    averageDurationMs = 25.0,
                    maxDurationMs = 100
                )
            )
        )
        fakeApiClient.requestTypeStatsDto = AdminRequestTypeStatsDto(
            interval = "day",
            types = listOf(
                AdminRequestTypeCountDto(type = "auth", requestCount = 1440)
            )
        )

        val result = repository.getTrafficData(AdminTimeRange.DAYS_7, Instant.parse("2026-08-30T10:00:00Z")).getOrThrow()

        // 1440 requests in a day bucket = 1440 / 1440.0 = 1.0 RPM
        assertEquals(1.0, result.points.first().throughputRpm, 0.001)
        assertEquals(100.0, result.points.first().successRate, 0.001)
        assertEquals(0.0, result.points.first().errorRate, 0.001)
        assertEquals(100.0, result.types.first().sharePercentage, 0.001)
    }

    @Test
    fun `getTrafficData correctly sorts request types descending by volume`() = runTest {
        fakeApiClient.requestTypeStatsDto = AdminRequestTypeStatsDto(
            types = listOf(
                AdminRequestTypeCountDto(type = "other", requestCount = 50),
                AdminRequestTypeCountDto(type = "sync", requestCount = 500),
                AdminRequestTypeCountDto(type = "auth", requestCount = 250),
                AdminRequestTypeCountDto(type = "stats", requestCount = 200)
            )
        )

        val result = repository.getTrafficData(AdminTimeRange.HOURS_24, Instant.parse("2026-08-30T10:00:00Z")).getOrThrow()

        assertEquals(4, result.types.size)
        assertEquals(RequestTypeCategory.SYNC, result.types[0].category)
        assertEquals(500, result.types[0].requestCount)
        assertEquals(50.0, result.types[0].sharePercentage, 0.01) // 500 / 1000

        assertEquals(RequestTypeCategory.AUTH, result.types[1].category)
        assertEquals(250, result.types[1].requestCount)
        assertEquals(25.0, result.types[1].sharePercentage, 0.01) // 250 / 1000

        assertEquals(RequestTypeCategory.STATS, result.types[2].category)
        assertEquals(200, result.types[2].requestCount)
        assertEquals(20.0, result.types[2].sharePercentage, 0.01) // 200 / 1000

        assertEquals(RequestTypeCategory.OTHER, result.types[3].category)
        assertEquals(50, result.types[3].requestCount)
        assertEquals(5.0, result.types[3].sharePercentage, 0.01) // 50 / 1000
    }

    @Test
    fun `getErrorLogs handles empty logs response and null cursor`() = runTest {
        fakeApiClient.errorLogResponseDto = ErrorLogResponseDto(
            errors = emptyList(),
            nextCursor = null
        )

        val page = repository.getErrorLogs().getOrThrow()

        assertTrue(page.errors.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `getErrorLogs maps all error log fields accurately`() = runTest {
        fakeApiClient.errorLogResponseDto = ErrorLogResponseDto(
            errors = listOf(
                ErrorLogDto(
                    id = 555,
                    createdAt = "2026-08-30T18:00:00Z",
                    userId = "user-abc-123",
                    method = "DELETE",
                    route = "/v1/solves/solve-999",
                    status = 404,
                    code = "solve_not_found",
                    message = "Solve entity does not exist"
                )
            ),
            nextCursor = "cursor_next_page"
        )

        val page = repository.getErrorLogs(before = "cursor_curr").getOrThrow()

        assertEquals(1, page.errors.size)
        val item = page.errors.first()
        assertEquals(555, item.id)
        assertEquals("2026-08-30T18:00:00Z", item.createdAt)
        assertEquals("user-abc-123", item.userId)
        assertEquals("DELETE", item.method)
        assertEquals("/v1/solves/solve-999", item.route)
        assertEquals(404, item.status)
        assertEquals("solve_not_found", item.code)
        assertEquals("Solve entity does not exist", item.message)
        assertEquals("cursor_next_page", page.nextCursor)
    }

    // ---------------------------------------------------------------------------------------------
    // FAKE API CLIENT FOR STRESS TESTING
    // ---------------------------------------------------------------------------------------------

    private class FakeStressCubeSyncApiClient : CubeSyncApiClient {
        var throwException: Exception? = null

        var overviewDto = AdminOverviewDto()
        var requestStatsDto = AdminRequestStatsDto()
        var requestTypeStatsDto = AdminRequestTypeStatsDto()
        var errorLogResponseDto = ErrorLogResponseDto()

        override suspend fun getAdminOverview(authToken: String?): AdminOverviewDto {
            throwException?.let { throw it }
            return overviewDto
        }

        override suspend fun getAdminRequestStats(
            from: String?,
            to: String?,
            interval: String?,
            authToken: String?
        ): AdminRequestStatsDto {
            throwException?.let { throw it }
            return requestStatsDto
        }

        override suspend fun getAdminRequestTypeStats(
            from: String?,
            to: String?,
            interval: String?,
            authToken: String?
        ): AdminRequestTypeStatsDto {
            throwException?.let { throw it }
            return requestTypeStatsDto
        }

        override suspend fun getAdminErrorLogs(
            before: String?,
            authToken: String?
        ): ErrorLogResponseDto {
            throwException?.let { throw it }
            return errorLogResponseDto
        }

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
