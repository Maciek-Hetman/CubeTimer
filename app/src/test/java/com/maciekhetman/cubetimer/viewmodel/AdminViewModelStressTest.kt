package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.admin.AdminRepository
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.admin.AdminErrorLogItem
import com.maciekhetman.cubetimer.model.admin.AdminErrorLogPage
import com.maciekhetman.cubetimer.model.admin.AdminOverview
import com.maciekhetman.cubetimer.model.admin.AdminRequestTypeItem
import com.maciekhetman.cubetimer.model.admin.AdminTimeRange
import com.maciekhetman.cubetimer.model.admin.AdminTrafficData
import com.maciekhetman.cubetimer.model.admin.AdminTrafficPoint
import com.maciekhetman.cubetimer.model.admin.RequestTypeCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Adversarial stress tests for [AdminViewModel] covering:
 * - 401 Unauthorized, 403 Forbidden, 500 Server Error behavior
 * - Partial error isolation and RBAC access denied flag propagation
 * - Deep multi-page cursor pagination traversal
 * - Pagination error recovery and concurrency debouncing
 * - Error log query filtering with edge cases, mixed-case, null fields, and regex characters
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdminViewModelStressTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var fakeRepository: FakeStressAdminRepository
    private lateinit var viewModel: AdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        fakeRepository = FakeStressAdminRepository()
        viewModel = AdminViewModel(application = application, adminRepository = fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP ERROR HANDLING & ACCESS DENIED PROPAGATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `401 Unauthorized sets all sub-states to Error but does NOT trigger isAccessDenied`() = testScope.runTest {
        fakeRepository.overviewResult = Result.failure(AuthException.Unauthorized("Token expired"))
        fakeRepository.trafficResult = Result.failure(AuthException.Unauthorized("Token expired"))
        fakeRepository.errorsResult = Result.failure(AuthException.Unauthorized("Token expired"))

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("401 Unauthorized should not trigger 403 isAccessDenied", state.isAccessDenied)
        assertTrue(state.overviewState is OverviewUiState.Error)
        assertEquals("Token expired", (state.overviewState as OverviewUiState.Error).message)
        assertFalse((state.overviewState as OverviewUiState.Error).isForbidden)

        assertTrue(state.trafficState is TrafficUiState.Error)
        assertEquals("Token expired", (state.trafficState as TrafficUiState.Error).message)

        assertTrue(state.errorsState is ErrorsUiState.Error)
        assertEquals("Token expired", (state.errorsState as ErrorsUiState.Error).message)
    }

    @Test
    fun `403 Forbidden sets all sub-states to Error and triggers isAccessDenied`() = testScope.runTest {
        fakeRepository.overviewResult = Result.failure(AuthException.Forbidden("Forbidden: Non-admin user"))
        fakeRepository.trafficResult = Result.failure(AuthException.Forbidden("Forbidden: Non-admin user"))
        fakeRepository.errorsResult = Result.failure(AuthException.Forbidden("Forbidden: Non-admin user"))

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("403 Forbidden must trigger isAccessDenied", state.isAccessDenied)
        assertTrue(state.overviewState is OverviewUiState.Error)
        assertTrue((state.overviewState as OverviewUiState.Error).isForbidden)

        assertTrue(state.trafficState is TrafficUiState.Error)
        assertTrue((state.trafficState as TrafficUiState.Error).isForbidden)

        assertTrue(state.errorsState is ErrorsUiState.Error)
        assertTrue((state.errorsState as ErrorsUiState.Error).isForbidden)
    }

    @Test
    fun `500 Server Error sets sub-states to Error without triggering isAccessDenied`() = testScope.runTest {
        fakeRepository.overviewResult = Result.failure(AuthException.ApiError("internal_error", "DB error 500", 500))
        fakeRepository.trafficResult = Result.failure(AuthException.ApiError("internal_error", "Aggregator 500", 500))
        fakeRepository.errorsResult = Result.failure(AuthException.ApiError("internal_error", "Log server 500", 500))

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isAccessDenied)
        assertTrue(state.overviewState is OverviewUiState.Error)
        assertTrue(state.trafficState is TrafficUiState.Error)
        assertTrue(state.errorsState is ErrorsUiState.Error)
    }

    @Test
    fun `partial endpoint failures isolate errors correctly`() = testScope.runTest {
        // Overview fails with 500, but Traffic and Errors succeed
        fakeRepository.overviewResult = Result.failure(AuthException.ApiError("internal_error", "Overview offline", 500))
        fakeRepository.trafficResult = Result.success(fakeRepository.defaultTraffic)
        fakeRepository.errorsResult = Result.success(fakeRepository.defaultErrorsPage)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isAccessDenied)
        assertTrue(state.overviewState is OverviewUiState.Error)
        assertTrue(state.trafficState is TrafficUiState.Success)
        assertTrue(state.errorsState is ErrorsUiState.Success)
    }

    @Test
    fun `time range switch failure marks traffic as Error and updates isAccessDenied on 403`() = testScope.runTest {
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.trafficState is TrafficUiState.Success)

        fakeRepository.trafficResult = Result.failure(AuthException.Forbidden("Admin revoked"))
        viewModel.setTimeRange(AdminTimeRange.DAYS_30)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isAccessDenied)
        assertTrue(state.trafficState is TrafficUiState.Error)
        assertTrue((state.trafficState as TrafficUiState.Error).isForbidden)
    }

    // ---------------------------------------------------------------------------------------------
    // CURSOR PAGINATION & CONCURRENCY DEBOUNCING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `deep multi-page pagination traverses pages sequentially until null cursor`() = testScope.runTest {
        // Initial page (Page 1)
        fakeRepository.errorsResult = Result.success(
            AdminErrorLogPage(
                errors = (1..5).map { createSampleError(it.toLong(), "Page 1 - Error $it") },
                nextCursor = "cursor-page-2"
            )
        )
        viewModel.refresh()
        advanceUntilIdle()

        val statePage1 = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(5, statePage1.errors.size)
        assertEquals("cursor-page-2", statePage1.nextCursor)
        assertFalse(statePage1.isLoadingMore)

        // Load Page 2
        fakeRepository.errorsResult = Result.success(
            AdminErrorLogPage(
                errors = (6..10).map { createSampleError(it.toLong(), "Page 2 - Error $it") },
                nextCursor = "cursor-page-3"
            )
        )
        viewModel.loadMoreErrors()
        advanceUntilIdle()

        val statePage2 = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(10, statePage2.errors.size)
        assertEquals("cursor-page-3", statePage2.nextCursor)
        assertFalse(statePage2.isLoadingMore)

        // Load Page 3 (Final page)
        fakeRepository.errorsResult = Result.success(
            AdminErrorLogPage(
                errors = (11..15).map { createSampleError(it.toLong(), "Page 3 - Error $it") },
                nextCursor = null
            )
        )
        viewModel.loadMoreErrors()
        advanceUntilIdle()

        val statePage3 = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(15, statePage3.errors.size)
        assertNull(statePage3.nextCursor)
        assertFalse(statePage3.isLoadingMore)

        // Subsequent loadMoreErrors when nextCursor is null must be a no-op
        val previousCallCount = fakeRepository.getErrorLogsCallCount
        viewModel.loadMoreErrors()
        advanceUntilIdle()
        assertEquals("Should not make network call when nextCursor is null", previousCallCount, fakeRepository.getErrorLogsCallCount)
    }

    @Test
    fun `pagination failure recovers gracefully and retains existing pages`() = testScope.runTest {
        fakeRepository.errorsResult = Result.success(
            AdminErrorLogPage(
                errors = (1..5).map { createSampleError(it.toLong(), "Page 1 - Error $it") },
                nextCursor = "cursor-page-2"
            )
        )
        viewModel.refresh()
        advanceUntilIdle()

        // Page 2 fails with network timeout
        fakeRepository.errorsResult = Result.failure(AuthException.NetworkError("Timeout"))
        viewModel.loadMoreErrors()
        advanceUntilIdle()

        val state = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals("Existing page 1 errors must be retained on page 2 load failure", 5, state.errors.size)
        assertEquals("cursor-page-2", state.nextCursor)
        assertFalse("isLoadingMore must reset to false on failure", state.isLoadingMore)
    }

    @Test
    fun `refresh resets paginated errors back to page 1`() = testScope.runTest {
        fakeRepository.errorsResult = Result.success(
            AdminErrorLogPage(
                errors = (1..10).map { createSampleError(it.toLong(), "Error $it") },
                nextCursor = "cursor-page-2"
            )
        )
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(10, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).errors.size)

        // Refresh with fresh initial 3 errors
        fakeRepository.errorsResult = Result.success(
            AdminErrorLogPage(
                errors = (100..102).map { createSampleError(it.toLong(), "New Error $it") },
                nextCursor = "fresh-cursor"
            )
        )
        viewModel.refresh()
        advanceUntilIdle()

        val refreshed = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(3, refreshed.errors.size)
        assertEquals(100L, refreshed.errors.first().id)
        assertEquals("fresh-cursor", refreshed.nextCursor)
    }

    // ---------------------------------------------------------------------------------------------
    // QUERY FILTERING EDGE CASES & STRESS TESTING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `query filter handles all dimensions and special characters without crash`() = testScope.runTest {
        val testErrors = listOf(
            AdminErrorLogItem(id = 1, createdAt = "2026-08-30T10:00:00Z", userId = "user-alice", method = "POST", route = "/v1/sync", status = 409, code = "cursor_expired", message = "Client cursor behind"),
            AdminErrorLogItem(id = 2, createdAt = "2026-08-30T10:05:00Z", userId = "user-bob", method = "GET", route = "/v1/solves", status = 500, code = "internal_error", message = "Database lock timeout"),
            AdminErrorLogItem(id = 3, createdAt = "2026-08-30T10:10:00Z", userId = null, method = "POST", route = "/v1/auth/login", status = 401, code = "invalid_credentials", message = "Invalid email or password"),
            AdminErrorLogItem(id = 4, createdAt = "2026-08-30T10:15:00Z", userId = "user-charlie", method = "DELETE", route = "/v1/sessions/session-42", status = 404, code = "session_not_found", message = "Session not found [id=42]"),
            AdminErrorLogItem(id = 5, createdAt = "2026-08-30T10:20:00Z", userId = "user-david", method = "POST", route = "/v1/snapshot", status = 429, code = "rate_limited", message = "Too many requests (+100/min)")
        )

        fakeRepository.errorsResult = Result.success(AdminErrorLogPage(errors = testErrors, nextCursor = null))
        viewModel.refresh()
        advanceUntilIdle()

        val successState = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(5, successState.filteredErrors.size)

        // Filter by user ID with null handling
        viewModel.setErrorFilter("alice")
        assertEquals(1, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
        assertEquals("user-alice", (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.first().userId)

        // Filter by HTTP method (case-insensitive)
        viewModel.setErrorFilter("delete")
        assertEquals(1, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
        assertEquals("DELETE", (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.first().method)

        // Filter by status code
        viewModel.setErrorFilter("500")
        assertEquals(1, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
        assertEquals(500, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.first().status)

        // Filter by error code
        viewModel.setErrorFilter("rate_limited")
        assertEquals(1, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
        assertEquals("rate_limited", (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.first().code)

        // Filter with special regex characters "[id=42]"
        viewModel.setErrorFilter("[id=42]")
        assertEquals(1, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
        assertEquals(4L, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.first().id)

        // Filter with special regex characters "(+100/min)"
        viewModel.setErrorFilter("(+100/min)")
        assertEquals(1, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
        assertEquals(5L, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.first().id)

        // Whitespace and blank returns all
        viewModel.setErrorFilter("   ")
        assertEquals(5, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)

        viewModel.setErrorFilter("")
        assertEquals(5, (viewModel.uiState.value.errorsState as ErrorsUiState.Success).filteredErrors.size)
    }

    private fun createSampleError(id: Long, msg: String): AdminErrorLogItem =
        AdminErrorLogItem(
            id = id,
            createdAt = "2026-08-30T12:00:00Z",
            userId = "user-$id",
            method = "POST",
            route = "/v1/sync",
            status = 500,
            code = "error_$id",
            message = msg
        )

    // ---------------------------------------------------------------------------------------------
    // FAKE ADMIN REPOSITORY FOR STRESS TESTING
    // ---------------------------------------------------------------------------------------------

    private class FakeStressAdminRepository : AdminRepository {
        var getOverviewCallCount = 0
        var getTrafficCallCount = 0
        var getErrorLogsCallCount = 0

        val defaultOverview = AdminOverview(
            totalUsers = 100,
            verifiedUsers = 85,
            newUsers24h = 5,
            newUsers7d = 15,
            newUsers30d = 30,
            activeUsers24h = 40,
            activeUsers7d = 70,
            activeUsers30d = 90,
            totalDevices = 120,
            totalSessions = 250,
            totalSolves = 5000
        )

        val defaultTraffic = AdminTrafficData(
            from = "2026-08-29T10:00:00Z",
            to = "2026-08-30T10:00:00Z",
            interval = "hour",
            totalRequests = 500,
            totalSuccess = 480,
            totalErrors = 20,
            overallAvgLatencyMs = 45.0,
            points = listOf(
                AdminTrafficPoint(
                    bucket = "10:00",
                    requestCount = 100,
                    status2xx = 95,
                    status3xx = 0,
                    status4xx = 3,
                    status5xx = 2,
                    averageDurationMs = 40.0,
                    maxDurationMs = 120,
                    throughputRpm = 10.0,
                    successRate = 0.95,
                    errorRate = 0.05
                )
            ),
            types = listOf(
                AdminRequestTypeItem(category = RequestTypeCategory.SYNC, requestCount = 300, sharePercentage = 60.0),
                AdminRequestTypeItem(category = RequestTypeCategory.AUTH, requestCount = 200, sharePercentage = 40.0)
            )
        )

        val defaultErrorsPage = AdminErrorLogPage(
            errors = listOf(
                AdminErrorLogItem(id = 1, createdAt = "2026-08-30T10:00:00Z", method = "POST", route = "/v1/sync", status = 409, code = "cursor_expired", message = "Expired", userId = "u1")
            ),
            nextCursor = null
        )

        var overviewResult: Result<AdminOverview> = Result.success(defaultOverview)
        var trafficResult: Result<AdminTrafficData> = Result.success(defaultTraffic)
        var errorsResult: Result<AdminErrorLogPage> = Result.success(defaultErrorsPage)

        override suspend fun getOverview(): Result<AdminOverview> {
            getOverviewCallCount++
            return overviewResult
        }

        override suspend fun getTrafficData(range: AdminTimeRange, now: Instant): Result<AdminTrafficData> {
            getTrafficCallCount++
            return trafficResult
        }

        override suspend fun getErrorLogs(before: String?): Result<AdminErrorLogPage> {
            getErrorLogsCallCount++
            return errorsResult
        }
    }
}
