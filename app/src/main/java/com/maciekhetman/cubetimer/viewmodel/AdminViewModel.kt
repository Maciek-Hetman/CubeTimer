package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maciekhetman.cubetimer.CubeTimerApplication
import com.maciekhetman.cubetimer.data.admin.AdminRepository
import com.maciekhetman.cubetimer.data.admin.AdminRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.admin.AdminErrorLogItem
import com.maciekhetman.cubetimer.model.admin.AdminOverview
import com.maciekhetman.cubetimer.model.admin.AdminTimeRange
import com.maciekhetman.cubetimer.model.admin.AdminTrafficData
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminTab(val label: String) {
    OVERVIEW("Overview"),
    TRAFFIC("Traffic"),
    ERRORS("Error Logs")
}

sealed interface OverviewUiState {
    data object Loading : OverviewUiState
    data class Success(val overview: AdminOverview) : OverviewUiState
    data class Error(val message: String, val isForbidden: Boolean = false) : OverviewUiState
}

sealed interface TrafficUiState {
    data object Loading : TrafficUiState
    data class Success(val traffic: AdminTrafficData) : TrafficUiState
    data class Error(val message: String, val isForbidden: Boolean = false) : TrafficUiState
}

sealed interface ErrorsUiState {
    data object Loading : ErrorsUiState
    data class Success(
        val errors: List<AdminErrorLogItem>,
        val nextCursor: String?,
        val isLoadingMore: Boolean = false,
        val filterQuery: String = ""
    ) : ErrorsUiState {
        val filteredErrors: List<AdminErrorLogItem>
            get() = if (filterQuery.isBlank()) errors else {
                val query = filterQuery.trim().lowercase()
                errors.filter {
                    it.route.lowercase().contains(query) ||
                        it.code.lowercase().contains(query) ||
                        it.message.lowercase().contains(query) ||
                        it.method.lowercase().contains(query) ||
                        (it.userId?.lowercase()?.contains(query) == true) ||
                        it.status.toString().contains(query)
                }
            }
    }
    data class Error(val message: String, val isForbidden: Boolean = false) : ErrorsUiState
}

data class AdminDashboardState(
    val selectedTab: AdminTab = AdminTab.OVERVIEW,
    val selectedRange: AdminTimeRange = AdminTimeRange.HOURS_24,
    val overviewState: OverviewUiState = OverviewUiState.Loading,
    val trafficState: TrafficUiState = TrafficUiState.Loading,
    val errorsState: ErrorsUiState = ErrorsUiState.Loading,
    val isRefreshing: Boolean = false,
    val isAccessDenied: Boolean = false
)

class AdminViewModel(
    application: Application,
    private val adminRepository: AdminRepository
) : AndroidViewModel(application) {

    // Default constructor for ViewModelProvider instantiation
    constructor(application: Application) : this(
        application = application,
        adminRepository = AdminRepositoryImpl(
            apiClient = (application as? CubeTimerApplication)?.apiClient
                ?: CubeTimerApplication.getInstance().apiClient
        )
    )

    private val _uiState = MutableStateFlow(AdminDashboardState())
    val uiState: StateFlow<AdminDashboardState> = _uiState.asStateFlow()

    init {
        loadAllData()
    }

    fun setTab(tab: AdminTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setTimeRange(range: AdminTimeRange) {
        if (_uiState.value.selectedRange == range && _uiState.value.trafficState is TrafficUiState.Success) return
        _uiState.update { it.copy(selectedRange = range, trafficState = TrafficUiState.Loading) }
        loadTrafficData(range)
    }

    fun setErrorFilter(query: String) {
        val currentErrors = _uiState.value.errorsState
        if (currentErrors is ErrorsUiState.Success) {
            _uiState.update { it.copy(errorsState = currentErrors.copy(filterQuery = query)) }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadAllData(isPullToRefresh = true)
    }

    fun loadMoreErrors() {
        val current = _uiState.value.errorsState
        if (current !is ErrorsUiState.Success || current.nextCursor == null || current.isLoadingMore) return

        _uiState.update { it.copy(errorsState = current.copy(isLoadingMore = true)) }
        viewModelScope.launch {
            adminRepository.getErrorLogs(before = current.nextCursor)
                .onSuccess { page ->
                    _uiState.update { state ->
                        val latest = state.errorsState
                        if (latest is ErrorsUiState.Success) {
                            state.copy(
                                errorsState = latest.copy(
                                    errors = latest.errors + page.errors,
                                    nextCursor = page.nextCursor,
                                    isLoadingMore = false
                                )
                            )
                        } else {
                            state
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        val latest = state.errorsState
                        if (latest is ErrorsUiState.Success) {
                            state.copy(errorsState = latest.copy(isLoadingMore = false))
                        } else {
                            state
                        }
                    }
                }
        }
    }

    fun loadAllData(isPullToRefresh: Boolean = false) {
        viewModelScope.launch {
            val overviewDeferred = async { adminRepository.getOverview() }
            val trafficDeferred = async { adminRepository.getTrafficData(_uiState.value.selectedRange) }
            val errorsDeferred = async { adminRepository.getErrorLogs() }

            val overviewResult = overviewDeferred.await()
            val trafficResult = trafficDeferred.await()
            val errorsResult = errorsDeferred.await()

            var accessDenied = false

            val newOverview = overviewResult.fold(
                onSuccess = { OverviewUiState.Success(it) },
                onFailure = { err ->
                    if (err is AuthException.Forbidden) accessDenied = true
                    OverviewUiState.Error(
                        err.message ?: "Failed to load overview",
                        isForbidden = err is AuthException.Forbidden
                    )
                }
            )

            val newTraffic = trafficResult.fold(
                onSuccess = { TrafficUiState.Success(it) },
                onFailure = { err ->
                    if (err is AuthException.Forbidden) accessDenied = true
                    TrafficUiState.Error(
                        err.message ?: "Failed to load traffic stats",
                        isForbidden = err is AuthException.Forbidden
                    )
                }
            )

            val newErrors = errorsResult.fold(
                onSuccess = { ErrorsUiState.Success(errors = it.errors, nextCursor = it.nextCursor) },
                onFailure = { err ->
                    if (err is AuthException.Forbidden) accessDenied = true
                    ErrorsUiState.Error(
                        err.message ?: "Failed to load error logs",
                        isForbidden = err is AuthException.Forbidden
                    )
                }
            )

            _uiState.update {
                it.copy(
                    overviewState = newOverview,
                    trafficState = newTraffic,
                    errorsState = newErrors,
                    isRefreshing = false,
                    isAccessDenied = accessDenied
                )
            }
        }
    }

    private fun loadTrafficData(range: AdminTimeRange) {
        viewModelScope.launch {
            adminRepository.getTrafficData(range)
                .onSuccess { data ->
                    _uiState.update { it.copy(trafficState = TrafficUiState.Success(data)) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            trafficState = TrafficUiState.Error(
                                err.message ?: "Failed to load traffic",
                                isForbidden = err is AuthException.Forbidden
                            ),
                            isAccessDenied = if (err is AuthException.Forbidden) true else it.isAccessDenied
                        )
                    }
                }
        }
    }
}
