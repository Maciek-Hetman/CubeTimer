package com.maciekhetman.cubetimer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.admin.AdminErrorLogItem
import com.maciekhetman.cubetimer.model.admin.AdminOverview
import com.maciekhetman.cubetimer.model.admin.AdminTimeRange
import com.maciekhetman.cubetimer.model.admin.AdminTrafficData
import com.maciekhetman.cubetimer.model.admin.AdminTrafficPoint
import com.maciekhetman.cubetimer.model.admin.RequestTypeCategory
import com.maciekhetman.cubetimer.viewmodel.AdminDashboardState
import com.maciekhetman.cubetimer.viewmodel.AdminTab
import com.maciekhetman.cubetimer.viewmodel.AdminViewModel
import com.maciekhetman.cubetimer.viewmodel.ErrorsUiState
import com.maciekhetman.cubetimer.viewmodel.OverviewUiState
import com.maciekhetman.cubetimer.viewmodel.TrafficUiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: AdminViewModel,
    authState: AuthState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val infiniteTransition = rememberInfiniteTransition(label = "admin_refresh")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "admin_refresh_spin"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Admin Metrics")
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            shape = RoundedCornerShape(16.dp),
                            label = { Text("ADMIN", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = if (uiState.isRefreshing) Modifier.rotate(rotation) else Modifier
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (authState !is AuthState.Admin || uiState.isAccessDenied) {
                AdminAccessDeniedContent(onNavigateBack = onNavigateBack)
            } else {
                AdminDashboardContent(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun AdminAccessDeniedContent(onNavigateBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Access Restricted",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Administrator privileges (user_role == 'admin') are required to access backend system metrics.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    shape = RoundedCornerShape(20.dp),
                    onClick = onNavigateBack
                ) {
                    Text("Return")
                }
            }
        }
    }
}

@Composable
private fun AdminDashboardContent(
    uiState: AdminDashboardState,
    viewModel: AdminViewModel
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = uiState.selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            AdminTab.entries.forEach { tab ->
                Tab(
                    selected = uiState.selectedTab == tab,
                    onClick = { viewModel.setTab(tab) },
                    text = { Text(tab.label) }
                )
            }
        }

        Crossfade(targetState = uiState.selectedTab, label = "admin_tab_crossfade") { tab ->
            when (tab) {
                AdminTab.OVERVIEW -> OverviewTabContent(uiState.overviewState)
                AdminTab.TRAFFIC -> TrafficTabContent(
                    state = uiState.trafficState,
                    selectedRange = uiState.selectedRange,
                    onRangeSelected = viewModel::setTimeRange
                )
                AdminTab.ERRORS -> ErrorsTabContent(
                    state = uiState.errorsState,
                    onSearchQueryChange = viewModel::setErrorFilter,
                    onLoadMore = viewModel::loadMoreErrors
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 1: OVERVIEW
// -------------------------------------------------------------------------------------------------

@Composable
private fun OverviewTabContent(state: OverviewUiState) {
    when (state) {
        is OverviewUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is OverviewUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
            }
        }
        is OverviewUiState.Success -> {
            val overview = state.overview
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Key Metric KPIs
                item {
                    Text(
                        text = "Platform Health & Engagement",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        KpiMiniCard(
                            title = "Verification Rate",
                            value = String.format(Locale.US, "%.1f%%", overview.verificationRate),
                            modifier = Modifier.weight(1f)
                        )
                        KpiMiniCard(
                            title = "DAU / MAU",
                            value = String.format(Locale.US, "%.1f%%", overview.dauMauRatio),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        KpiMiniCard(
                            title = "30d Active",
                            value = String.format(Locale.US, "%.1f%%", overview.activeRatio30d),
                            modifier = Modifier.weight(1f)
                        )
                        KpiMiniCard(
                            title = "Solves / Session",
                            value = String.format(Locale.US, "%.1f", overview.avgSolvesPerSession),
                            modifier = Modifier.weight(1f)
                        )
                        KpiMiniCard(
                            title = "Devices / User",
                            value = String.format(Locale.US, "%.2f", overview.avgDevicesPerUser),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Platform Totals Card
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Platform Totals",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            StatRow("Total Registered Users", "${overview.totalUsers}")
                            StatRow("Email Verified Users", "${overview.verifiedUsers}")
                            StatRow("Registered Devices", "${overview.totalDevices}")
                            StatRow("Total Sessions", "${overview.totalSessions}")
                            StatRow("Total Cloud Solves", "${overview.totalSolves}")
                        }
                    }
                }

                // User Growth & Activity
                item {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "User Growth & Activity",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "New Signups",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatRow("New Users (24h)", "+${overview.newUsers24h}")
                            StatRow("New Users (7d)", "+${overview.newUsers7d}")
                            StatRow("New Users (30d)", "+${overview.newUsers30d}")

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Active Users",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatRow("Active Users (24h)", "${overview.activeUsers24h}")
                            StatRow("Active Users (7d)", "${overview.activeUsers7d}")
                            StatRow("Active Users (30d)", "${overview.activeUsers30d}")
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 2: TRAFFIC & LATENCY
// -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrafficTabContent(
    state: TrafficUiState,
    selectedRange: AdminTimeRange,
    onRangeSelected: (AdminTimeRange) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Time Range Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AdminTimeRange.entries.forEach { range ->
                FilterChip(
                    selected = selectedRange == range,
                    onClick = { onRangeSelected(range) },
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(range.label) },
                    leadingIcon = if (selectedRange == range) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
        }

        when (state) {
            is TrafficUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is TrafficUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is TrafficUiState.Success -> {
                val traffic = state.traffic
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // KPI summary cards
                    item {
                        val succPct = if (traffic.totalRequests > 0) {
                            (traffic.totalSuccess.toDouble() / traffic.totalRequests.toDouble()) * 100.0
                        } else 100.0

                        val errPct = if (traffic.totalRequests > 0) {
                            (traffic.totalErrors.toDouble() / traffic.totalRequests.toDouble()) * 100.0
                        } else 0.0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            KpiMiniCard(
                                title = "Total Requests",
                                value = "${traffic.totalRequests}",
                                modifier = Modifier.weight(1f)
                            )
                            KpiMiniCard(
                                title = "Success Rate",
                                value = String.format(Locale.US, "%.1f%%", succPct),
                                modifier = Modifier.weight(1f)
                            )
                            KpiMiniCard(
                                title = "Error Rate",
                                value = String.format(Locale.US, "%.1f%%", errPct),
                                modifier = Modifier.weight(1f)
                            )
                            KpiMiniCard(
                                title = "Avg Latency",
                                value = String.format(Locale.US, "%.0fms", traffic.overallAvgLatencyMs),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Request Types Breakdown
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Request Type Distribution",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                RequestTypeDistributionBar(types = traffic.types)

                                Spacer(modifier = Modifier.height(12.dp))

                                traffic.types.forEach { typeItem ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(typeItem.category.toThemeColor())
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = typeItem.category.label,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        Text(
                                            text = "${typeItem.requestCount} (${String.format(Locale.US, "%.1f%%", typeItem.sharePercentage)})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Volume & Status Code Stacked Bar Chart
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Request Volume & Status Codes",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                val statusSuccessColor = MaterialTheme.colorScheme.primary
                                val statusRedirColor = MaterialTheme.colorScheme.secondary
                                val statusClientErrorColor = MaterialTheme.colorScheme.tertiary
                                val statusServerErrorColor = MaterialTheme.colorScheme.error

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    LegendIndicator(label = "2xx Success", color = statusSuccessColor)
                                    LegendIndicator(label = "3xx Redir", color = statusRedirColor)
                                    LegendIndicator(label = "4xx Client", color = statusClientErrorColor)
                                    LegendIndicator(label = "5xx Server", color = statusServerErrorColor)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                if (traffic.points.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(140.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No traffic points recorded for this period.")
                                    }
                                } else {
                                    VolumeStatusStackedBarChart(
                                        points = traffic.points,
                                        successColor = statusSuccessColor,
                                        redirColor = statusRedirColor,
                                        clientErrorColor = statusClientErrorColor,
                                        serverErrorColor = statusServerErrorColor,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Throughput RPM Line Chart
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Throughput (RPM)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                if (traffic.points.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No data points available.")
                                    }
                                } else {
                                    MetricSparklineChart(
                                        values = traffic.points.map { it.throughputRpm.toFloat() },
                                        lineColor = MaterialTheme.colorScheme.primary,
                                        unit = " RPM",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(130.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Latency Curve Line Chart
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Average Latency (ms)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                if (traffic.points.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No latency data available.")
                                    }
                                } else {
                                    MetricSparklineChart(
                                        values = traffic.points.map { it.averageDurationMs.toFloat() },
                                        lineColor = MaterialTheme.colorScheme.tertiary,
                                        unit = "ms",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(130.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// TAB 3: ERROR LOGS
// -------------------------------------------------------------------------------------------------

@Composable
private fun ErrorsTabContent(
    state: ErrorsUiState,
    onSearchQueryChange: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    when (state) {
        is ErrorsUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is ErrorsUiState.Error -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = state.message, color = MaterialTheme.colorScheme.error)
            }
        }
        is ErrorsUiState.Success -> {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search filter field
                OutlinedTextField(
                    value = state.filterQuery,
                    onValueChange = onSearchQueryChange,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Filter by route, code, method, user...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                val displayList = state.filteredErrors

                if (displayList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.filterQuery.isBlank()) "No error logs recorded." else "No error logs match '${state.filterQuery}'.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayList, key = { it.id }) { item ->
                            ErrorLogCard(item = item)
                        }

                        if (state.nextCursor != null) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (state.isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        Button(
                                            shape = RoundedCornerShape(20.dp),
                                            onClick = onLoadMore
                                        ) {
                                            Text("Load More Errors")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorLogCard(item: AdminErrorLogItem) {
    var expandedJson by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Method badge
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = item.method.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Status code badge
                    val isServerError = item.status >= 500
                    val badgeContainerColor = if (isServerError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                    val badgeContentColor = if (isServerError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    }
                    Surface(
                        color = badgeContainerColor,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "${item.status}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = item.route,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = formatTimestamp(item.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Code: ",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = item.code,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (item.userId != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "User: ${item.userId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { expandedJson = !expandedJson }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (expandedJson) "Hide JSON Details" else "View Details",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expandedJson) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = expandedJson) {
                val jsonText = """
{
  "id": ${item.id},
  "created_at": "${item.createdAt}",
  "user_id": ${if (item.userId != null) "\"${item.userId}\"" else "null"},
  "method": "${item.method}",
  "route": "${item.route}",
  "status": ${item.status},
  "code": "${item.code}",
  "message": "${item.message}"
}
                """.trimIndent()

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = jsonText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// CUSTOM COMPOSE CANVAS CHARTS & VISUALIZERS
// -------------------------------------------------------------------------------------------------

@Composable
private fun RequestTypeCategory.toThemeColor(): Color = when (this) {
    RequestTypeCategory.AUTH -> MaterialTheme.colorScheme.primary
    RequestTypeCategory.ACCOUNT -> MaterialTheme.colorScheme.secondary
    RequestTypeCategory.SYNC -> MaterialTheme.colorScheme.tertiary
    RequestTypeCategory.SNAPSHOT -> MaterialTheme.colorScheme.error
    RequestTypeCategory.SESSIONS -> MaterialTheme.colorScheme.primaryContainer
    RequestTypeCategory.STATS -> MaterialTheme.colorScheme.secondaryContainer
    RequestTypeCategory.OTHER -> MaterialTheme.colorScheme.outline
}

@Composable
private fun RequestTypeDistributionBar(
    types: List<com.maciekhetman.cubetimer.model.admin.AdminRequestTypeItem>,
    modifier: Modifier = Modifier
) {
    if (types.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(CircleShape)
    ) {
        types.forEach { item ->
            if (item.sharePercentage > 0) {
                Box(
                    modifier = Modifier
                        .weight(item.sharePercentage.toFloat().coerceAtLeast(0.01f))
                        .fillMaxSize()
                        .background(item.category.toThemeColor())
                )
            }
        }
    }
}

@Composable
private fun VolumeStatusStackedBarChart(
    points: List<AdminTrafficPoint>,
    successColor: Color = MaterialTheme.colorScheme.primary,
    redirColor: Color = MaterialTheme.colorScheme.secondary,
    clientErrorColor: Color = MaterialTheme.colorScheme.tertiary,
    serverErrorColor: Color = MaterialTheme.colorScheme.error,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val count = points.size
        if (count == 0) return@Canvas

        val maxVal = points.maxOfOrNull { it.requestCount }?.toFloat()?.coerceAtLeast(1f) ?: 1f
        val barWidth = (size.width / count) * 0.7f
        val gap = (size.width / count) * 0.3f

        points.forEachIndexed { i, p ->
            val x = i * (barWidth + gap) + gap / 2f
            val totalH = (p.requestCount / maxVal) * size.height

            var currentY = size.height

            // 2xx (Success)
            if (p.status2xx > 0) {
                val h2xx = (p.status2xx.toFloat() / maxVal) * size.height
                currentY -= h2xx
                drawRect(
                    color = successColor,
                    topLeft = Offset(x, currentY),
                    size = Size(barWidth, h2xx)
                )
            }

            // 3xx (Redir)
            if (p.status3xx > 0) {
                val h3xx = (p.status3xx.toFloat() / maxVal) * size.height
                currentY -= h3xx
                drawRect(
                    color = redirColor,
                    topLeft = Offset(x, currentY),
                    size = Size(barWidth, h3xx)
                )
            }

            // 4xx (Client Error)
            if (p.status4xx > 0) {
                val h4xx = (p.status4xx.toFloat() / maxVal) * size.height
                currentY -= h4xx
                drawRect(
                    color = clientErrorColor,
                    topLeft = Offset(x, currentY),
                    size = Size(barWidth, h4xx)
                )
            }

            // 5xx (Server Error)
            if (p.status5xx > 0) {
                val h5xx = (p.status5xx.toFloat() / maxVal) * size.height
                currentY -= h5xx
                drawRect(
                    color = serverErrorColor,
                    topLeft = Offset(x, currentY),
                    size = Size(barWidth, h5xx)
                )
            }
        }
    }
}

@Composable
private fun MetricSparklineChart(
    values: List<Float>,
    lineColor: Color,
    unit: String,
    guidelineColor: Color = MaterialTheme.colorScheme.outlineVariant,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas

        val maxVal = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val minVal = values.minOrNull() ?: 0f
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width

        // Draw guideline lines
        drawLine(
            color = guidelineColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1f
        )
        drawLine(
            color = guidelineColor,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1f
        )
        drawLine(
            color = guidelineColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f
        )

        val path = Path()
        values.forEachIndexed { index, v ->
            val normY = (v - minVal) / range
            val y = size.height - (normY * (size.height * 0.85f)) - (size.height * 0.05f)
            val x = index * stepX
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
    }
}

// -------------------------------------------------------------------------------------------------
// REUSABLE STATS UI COMPONENTS
// -------------------------------------------------------------------------------------------------

@Composable
private fun KpiMiniCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LegendIndicator(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTimestamp(iso: String): String {
    return if (iso.length >= 19) {
        iso.substring(11, 19)
    } else {
        iso
    }
}
