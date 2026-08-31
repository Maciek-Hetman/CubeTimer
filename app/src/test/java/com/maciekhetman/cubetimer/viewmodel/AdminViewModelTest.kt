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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AdminViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var fakeAdminRepository: FakeAdminRepository
    private lateinit var viewModel: AdminViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        fakeAdminRepository = FakeAdminRepository()
        viewModel = AdminViewModel(application = application, adminRepository = fakeAdminRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialLoadPopulatesOverviewTrafficAndErrors() = testScope.runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AdminTab.OVERVIEW, state.selectedTab)
        assertEquals(AdminTimeRange.HOURS_24, state.selectedRange)
        assertFalse(state.isAccessDenied)

        assertTrue(state.overviewState is OverviewUiState.Success)
        val overview = (state.overviewState as OverviewUiState.Success).overview
        assertEquals(100, overview.totalUsers)

        assertTrue(state.trafficState is TrafficUiState.Success)
        val traffic = (state.trafficState as TrafficUiState.Success).traffic
        assertEquals(500, traffic.totalRequests)

        assertTrue(state.errorsState is ErrorsUiState.Success)
        val errors = (state.errorsState as ErrorsUiState.Success).errors
        assertEquals(2, errors.size)
    }

    @Test
    fun testTabSwitching() {
        viewModel.setTab(AdminTab.TRAFFIC)
        assertEquals(AdminTab.TRAFFIC, viewModel.uiState.value.selectedTab)

        viewModel.setTab(AdminTab.ERRORS)
        assertEquals(AdminTab.ERRORS, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun testTimeRangeSwitchingReloadsTraffic() = testScope.runTest {
        advanceUntilIdle()
        assertEquals(1, fakeAdminRepository.getTrafficCallCount)

        viewModel.setTimeRange(AdminTimeRange.DAYS_7)
        advanceUntilIdle()

        assertEquals(2, fakeAdminRepository.getTrafficCallCount)
        assertEquals(AdminTimeRange.DAYS_7, viewModel.uiState.value.selectedRange)
    }

    @Test
    fun testErrorFilterQueryMatchesRouteAndMessage() = testScope.runTest {
        advanceUntilIdle()

        val initialSuccess = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(2, initialSuccess.filteredErrors.size)

        // Filter by route "/v1/sync"
        viewModel.setErrorFilter("sync")
        val syncFiltered = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(1, syncFiltered.filteredErrors.size)
        assertEquals("/v1/sync", syncFiltered.filteredErrors.first().route)

        // Filter by message "cursor"
        viewModel.setErrorFilter("cursor")
        val cursorFiltered = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(1, cursorFiltered.filteredErrors.size)
        assertEquals("cursor_expired", cursorFiltered.filteredErrors.first().code)

        // Filter non-matching string
        viewModel.setErrorFilter("non_existent_term")
        val emptyFiltered = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(0, emptyFiltered.filteredErrors.size)
    }

    @Test
    fun testErrorPaginationLoadMore() = testScope.runTest {
        fakeAdminRepository.errorLogsPage = AdminErrorLogPage(
            errors = listOf(
                AdminErrorLogItem(id = 1, createdAt = "2026-08-30T10:00:00Z", method = "POST", route = "/v1/sync", status = 409, code = "cursor_expired", message = "Expired", userId = "u1")
            ),
            nextCursor = "cursor-page-2"
        )

        viewModel.refresh()
        advanceUntilIdle()

        val state1 = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals("cursor-page-2", state1.nextCursor)
        assertEquals(1, state1.errors.size)

        // Setup page 2 response
        fakeAdminRepository.errorLogsPage = AdminErrorLogPage(
            errors = listOf(
                AdminErrorLogItem(id = 2, createdAt = "2026-08-30T10:05:00Z", method = "GET", route = "/v1/solves", status = 500, code = "internal_error", message = "DB error", userId = "u2")
            ),
            nextCursor = null
        )

        viewModel.loadMoreErrors()
        advanceUntilIdle()

        val state2 = viewModel.uiState.value.errorsState as ErrorsUiState.Success
        assertEquals(null, state2.nextCursor)
        assertEquals(2, state2.errors.size)
    }

    @Test
    fun testForbiddenResponseSetsAccessDeniedFlag() = testScope.runTest {
        fakeAdminRepository.shouldThrowForbidden = true
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isAccessDenied)
    }

    private class FakeAdminRepository : AdminRepository {
        var shouldThrowForbidden = false
        var getOverviewCallCount = 0
        var getTrafficCallCount = 0
        var getErrorLogsCallCount = 0

        var overview = AdminOverview(
            totalUsers = 100,
            verifiedUsers = 85,
            totalDevices = 120,
            totalSessions = 250,
            totalSolves = 5000,
            activeUsers24h = 40,
            activeUsers7d = 70,
            activeUsers30d = 90,
            newUsers24h = 5,
            newUsers7d = 15,
            newUsers30d = 30
        )

        var trafficData = AdminTrafficData(
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

        var errorLogsPage = AdminErrorLogPage(
            errors = listOf(
                AdminErrorLogItem(id = 1, createdAt = "2026-08-30T10:00:00Z", method = "POST", route = "/v1/sync", status = 409, code = "cursor_expired", message = "Expired", userId = "u1"),
                AdminErrorLogItem(id = 2, createdAt = "2026-08-30T10:05:00Z", method = "POST", route = "/v1/auth/login", status = 401, code = "invalid_credentials", message = "Invalid email or password", userId = null)
            ),
            nextCursor = null
        )

        override suspend fun getOverview(): Result<AdminOverview> {
            getOverviewCallCount++
            if (shouldThrowForbidden) return Result.failure(AuthException.Forbidden())
            return Result.success(overview)
        }

        override suspend fun getTrafficData(range: AdminTimeRange, now: Instant): Result<AdminTrafficData> {
            getTrafficCallCount++
            if (shouldThrowForbidden) return Result.failure(AuthException.Forbidden())
            return Result.success(trafficData)
        }

        override suspend fun getErrorLogs(before: String?): Result<AdminErrorLogPage> {
            getErrorLogsCallCount++
            if (shouldThrowForbidden) return Result.failure(AuthException.Forbidden())
            return Result.success(errorLogsPage)
        }
    }
}
