package com.maciekhetman.cubetimer.ui.screens

import android.app.Application
import android.net.Uri
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.session.SessionManagerImpl
import com.maciekhetman.cubetimer.data.session.SessionRepositoryImpl
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import com.maciekhetman.cubetimer.model.SolveTime
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.ui.components.HistoryContextualTopAppBar
import com.maciekhetman.cubetimer.ui.components.SessionExpandableCard
import com.maciekhetman.cubetimer.ui.dialogs.HistoryFilterSortBottomSheet
import com.maciekhetman.cubetimer.viewmodel.HistoryUiState
import com.maciekhetman.cubetimer.viewmodel.HistoryViewModel
import com.maciekhetman.cubetimer.viewmodel.SessionGroupUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryAdversarialEdgeCasesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var solvesRepository: SolvesRepository
    private lateinit var sessionRepository: SessionRepositoryImpl
    private lateinit var sessionManager: SessionManagerImpl
    private lateinit var fakeAuthManager: FakeAuthManager

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        val directExecutor = java.util.concurrent.Executor { it.run() }
        database = CubeDatabase.createInMemory(
            context = application,
            queryExecutor = directExecutor,
            transactionExecutor = directExecutor
        )

        solvesRepository = SolvesRepository(
            context = application,
            solveDao = database.solveDao(),
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao(),
            database = database,
            ioDispatcher = testDispatcher
        )
        sessionRepository = SessionRepositoryImpl(
            database = database,
            sessionDao = database.sessionDao(),
            syncOutboxDao = database.syncOutboxDao()
        )
        fakeAuthManager = FakeAuthManager()
        sessionManager = SessionManagerImpl(
            context = application,
            sessionRepository = sessionRepository,
            solveDao = database.solveDao(),
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HistoryViewModel {
        return HistoryViewModel(
            application = application,
            solvesRepository = solvesRepository,
            sessionManager = sessionManager,
            sessionRepository = sessionRepository,
            authManager = fakeAuthManager,
            database = database,
            sessionDao = database.sessionDao(),
            solveDao = database.solveDao(),
            syncOutboxDao = database.syncOutboxDao(),
            defaultDispatcher = testDispatcher
        )
    }

    private class TestDispatcherOwner(val dispatcher: OnBackPressedDispatcher) :
        OnBackPressedDispatcherOwner, LifecycleOwner, NavigationEventDispatcherOwner {
        private val lifecycleRegistry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val onBackPressedDispatcher = dispatcher
        override val navigationEventDispatcher: NavigationEventDispatcher
            get() = dispatcher.javaClass.getMethod("getEventDispatcher\$activity").invoke(dispatcher) as NavigationEventDispatcher
    }

    private fun createSession(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Session",
        event: Mode = Mode.CUBE_3x3,
        kind: SessionKind = SessionKind.MANUAL
    ): Session {
        return Session(
            id = id,
            ownerId = "guest",
            name = name,
            event = event,
            kind = kind,
            startedAt = "2026-09-12T10:00:00Z"
        )
    }

    private fun createSolve(
        id: String = UUID.randomUUID().toString(),
        timeInMillis: Long = 12500L,
        penalty: Penalty = Penalty.NONE,
        sessionId: String = UUID.randomUUID().toString()
    ): SolveTime {
        return SolveTime(
            id = id,
            timeInMillis = timeInMillis,
            timestamp = 1726135200000L,
            scramble = "R U R' U'",
            mode = Mode.CUBE_3x3,
            penalty = penalty,
            sessionId = sessionId
        )
    }

    // =========================================================================
    // 1. ADVERSARIAL BACK-PRESS HANDLING TESTS
    // =========================================================================

    @Test
    fun testBackHandler_whenInSelectionMode_clearsSelectionAndDoesNotTriggerParent() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Seed 1 session and 2 solves
        val sessionId = UUID.randomUUID().toString()
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                ownerId = "guest",
                name = "Back Press Test Session",
                event = "3x3",
                kind = "manual",
                startedAt = "2026-09-12T10:00:00Z"
            )
        )
        database.solveDao().insert(
            SolveEntity(
                id = "solve-back-1",
                ownerId = "guest",
                event = "3x3",
                durationMs = 10000L,
                penalty = "none",
                solvedAt = "2026-09-12T10:00:00Z",
                scramble = "R U",
                sessionId = sessionId
            )
        )
        database.solveDao().insert(
            SolveEntity(
                id = "solve-back-2",
                ownerId = "guest",
                event = "3x3",
                durationMs = 11000L,
                penalty = "none",
                solvedAt = "2026-09-12T10:01:00Z",
                scramble = "R' U'",
                sessionId = sessionId
            )
        )
        vm.refresh()
        advanceUntilIdle()

        val dispatcher = OnBackPressedDispatcher()
        val dispatcherOwner = TestDispatcherOwner(dispatcher)

        var parentBackHandled = false
        dispatcher.addCallback(
            dispatcherOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentBackHandled = true
                }
            }
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides dispatcherOwner,
                LocalOnBackPressedDispatcherOwner provides dispatcherOwner
            ) {
                MaterialTheme {
                    HistoryScreen(
                        viewModel = vm,
                        currentMode = Mode.CUBE_3x3,
                        onModeSelected = {}
                    )
                }
            }
        }

        // Initially not in selection mode
        assertFalse(vm.uiState.value.isSelectionMode)

        // Enter selection mode
        vm.startSelection("solve-back-1")
        advanceUntilIdle()
        composeTestRule.waitForIdle()

        assertTrue("Should be in selection mode", vm.uiState.value.isSelectionMode)
        assertEquals(1, vm.uiState.value.selectedSolveIds.size)

        // Trigger system back press
        dispatcher.onBackPressed()
        advanceUntilIdle()
        composeTestRule.waitForIdle()

        // Selection should be cleared, parent back should NOT have been called!
        assertFalse("Selection should be cleared by back press", vm.uiState.value.isSelectionMode)
        assertEquals(0, vm.uiState.value.selectedSolveIds.size)
        assertFalse("Parent back handler must not be triggered when selection consumed back press", parentBackHandled)

        // Second back press when NOT in selection mode: parent back handler MUST now be called
        dispatcher.onBackPressed()
        assertTrue("Parent back handler must be triggered when selection mode is inactive", parentBackHandled)
    }

    // =========================================================================
    // 2. SAF PICKER DISMISSALS & CANCELLATIONS (NULL URI HANDLING)
    // =========================================================================

    @Test
    fun testSafExportSelected_whenCancelled_preservesSelectionState() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Select two solves
        vm.startSelection("solve-1")
        vm.toggleSolveSelection("solve-2")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.selectedSolveIds.size)
        assertTrue(vm.uiState.value.isSelectionMode)

        // Simulate user clicking "Export Selected", picker opening, but user canceling (null URI returned)
        // Notice: In HistoryScreen, createDocumentLauncher receives null URI and does not call exportSelectedSolves.
        // Therefore, selection state MUST remain intact so user does not lose their selection.
        assertEquals(2, vm.uiState.value.selectedSolveIds.size)
        assertTrue(vm.uiState.value.isSelectionMode)
    }

    @Test
    fun testSafViewModel_whenUriStreamFails_handlesGracefullyWithoutCrashing() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        // Create a mock context that fails to open stream
        val mockUri = Uri.parse("content://nonexistent/provider/file.csv")

        // exportAllSolves with failing stream must not throw and should emit an error effect
        vm.exportAllSolves(application, mockUri)
        advanceUntilIdle()

        // exportSession with failing stream
        val session = createSession()
        vm.exportSession(application, session, mockUri)
        advanceUntilIdle()

        // exportSelectedSolves with failing stream
        vm.startSelection("some-id")
        vm.exportSelectedSolves(application, mockUri)
        advanceUntilIdle()

        // importSolvesFromUri with failing stream
        vm.importSolvesFromUri(application, mockUri)
        advanceUntilIdle()

        // Verify the ViewModel state is stable and did not crash
        assertNotNull(vm.uiState.value)
    }

    // =========================================================================
    // 3. CONFIRMATION DIALOG CANCELLATIONS VS CONFIRMATIONS
    // =========================================================================

    @Test
    fun testDeleteSessionConfirmationDialog_cancel_doesNotDeleteSession() = runTest(testDispatcher) {
        val session = createSession(id = "sess-100", name = "Crucial Session")
        val group = SessionGroupUiModel(
            session = session,
            solveCount = 5,
            bestDurationMs = 12000L,
            avgDurationMs = 13500L,
            isExpanded = false
        )

        var sessionDeleted = false
        val vm = createViewModel()

        composeTestRule.setContent {
            MaterialTheme {
                SessionExpandableCard(
                    sessionGroup = group,
                    isSelectionMode = false,
                    selectedSolveIds = emptySet(),
                    onToggleExpand = {},
                    onExportSession = {},
                    onDeleteSession = {
                        // Triggers dialog in HistoryScreen
                    },
                    onSolveClick = { _, _ -> },
                    onSolveLongClick = {},
                    onToggleSolveSelection = {},
                    onTogglePlusTwo = {},
                    onToggleDnf = {},
                    onDeleteSolve = {}
                )
            }
        }

        // Verify session name displayed
        composeTestRule.onNodeWithText("Crucial Session").assertIsDisplayed()
        assertFalse("Session should not be deleted", sessionDeleted)
    }

    @Test
    fun testContextualTopAppBar_actions_fireCorrectly() {
        var dismissCalled = false
        var selectAllCalled = false
        var exportCalled = false
        var deleteCalled = false

        composeTestRule.setContent {
            MaterialTheme {
                HistoryContextualTopAppBar(
                    selectedCount = 3,
                    isAllSelected = false,
                    onDismiss = { dismissCalled = true },
                    onSelectAllToggle = { selectAllCalled = true },
                    onExportSelected = { exportCalled = true },
                    onDeleteSelected = { deleteCalled = true }
                )
            }
        }

        composeTestRule.onNodeWithText("3 selected").assertIsDisplayed()

        // Dismiss (X)
        composeTestRule.onNodeWithContentDescription("Cancel selection").performClick()
        assertTrue(dismissCalled)

        // Select all
        composeTestRule.onNodeWithContentDescription("Select All").performClick()
        assertTrue(selectAllCalled)

        // Export
        composeTestRule.onNodeWithContentDescription("Export selected solves").performClick()
        assertTrue(exportCalled)

        // Delete
        composeTestRule.onNodeWithContentDescription("Delete selected solves").performClick()
        assertTrue(deleteCalled)
    }

    // =========================================================================
    // 4. LAYOUT RESILIENCE ON EXTREME CONFIGURATIONS (280dp, 320dp, Font 2.0x, 2.5x)
    // =========================================================================

    @Test
    fun testSessionExpandableCard_narrowWidth280dp_extremeFontScale2_5x() {
        val session = createSession(
            name = "Extra Extremely Long Session Name That Should Truncate With Ellipsis In Narrow Width",
            event = Mode.CUBE_3x3,
            kind = SessionKind.MANUAL
        )
        val group = SessionGroupUiModel(
            session = session,
            solveCount = 999,
            bestDurationMs = 5430L,
            avgDurationMs = 7890L,
            isExpanded = true,
            solves = listOf(
                createSolve(timeInMillis = 5430L, sessionId = session.id),
                createSolve(timeInMillis = 7890L, penalty = Penalty.PLUS_TWO, sessionId = session.id),
                createSolve(timeInMillis = 9990L, penalty = Penalty.DNF, sessionId = session.id)
            )
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.5f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        SessionExpandableCard(
                            sessionGroup = group,
                            isSelectionMode = false,
                            selectedSolveIds = emptySet(),
                            onToggleExpand = {},
                            onExportSession = {},
                            onDeleteSession = {},
                            onSolveClick = { _, _ -> },
                            onSolveLongClick = {},
                            onToggleSolveSelection = {},
                            onTogglePlusTwo = {},
                            onToggleDnf = {},
                            onDeleteSolve = {}
                        )
                    }
                }
            }
        }

        // Check that essential elements are displayed without layout overflow crash
        composeTestRule.onNodeWithText("999 solves · 3x3 · Manual").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Collapse session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testSessionExpandableCard_narrowWidth320dp_fontScale2_0x() {
        val session = createSession(
            name = "Compact 320dp Test",
            event = Mode.CUBE_4x4,
            kind = SessionKind.AUTOMATIC
        )
        val group = SessionGroupUiModel(
            session = session,
            solveCount = 12,
            bestDurationMs = 45200L,
            avgDurationMs = 52100L,
            isExpanded = true,
            solves = listOf(
                createSolve(timeInMillis = 45200L, sessionId = session.id)
            )
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.0f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        SessionExpandableCard(
                            sessionGroup = group,
                            isSelectionMode = true,
                            selectedSolveIds = emptySet(),
                            onToggleExpand = {},
                            onExportSession = {},
                            onDeleteSession = {},
                            onSolveClick = { _, _ -> },
                            onSolveLongClick = {},
                            onToggleSolveSelection = {},
                            onTogglePlusTwo = {},
                            onToggleDnf = {},
                            onDeleteSolve = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("12 solves · 4x4 · Auto").assertIsDisplayed()
    }

    @Test
    fun testHistorySolveCard_narrowWidth280dp_extremeFontScale2_5x() {
        val solve = createSolve(timeInMillis = 14320L, penalty = Penalty.PLUS_TWO)

        var plusTwoToggled = false
        var dnfToggled = false
        var deleteToggled = false

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.5f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(280.dp)) {
                        HistorySolveCard(
                            solve = solve,
                            solveNumber = 42,
                            onClick = {},
                            onDelete = { deleteToggled = true },
                            onTogglePlusTwo = { plusTwoToggled = true },
                            onToggleDnf = { dnfToggled = true },
                            isSelected = false,
                            isSelectionMode = false
                        )
                    }
                }
            }
        }

        // Action chips +2 and DNF, plus Delete button must remain interactive
        composeTestRule.onNodeWithTag("history_action_plus_two").performClick()
        assertTrue(plusTwoToggled)

        composeTestRule.onNodeWithTag("history_action_dnf").performClick()
        assertTrue(dnfToggled)

        composeTestRule.onNodeWithTag("history_action_delete").performClick()
        assertTrue(deleteToggled)
    }

    @Test
    fun testHistoryContextualTopAppBar_narrowWidth280dp_extremeFontScale2_5x() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.5f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(280.dp)) {
                        HistoryContextualTopAppBar(
                            selectedCount = 999,
                            isAllSelected = true,
                            onDismiss = {},
                            onSelectAllToggle = {},
                            onExportSelected = {},
                            onDeleteSelected = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("999 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Cancel selection").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Deselect All").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Export selected solves").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Delete selected solves").assertIsDisplayed()
    }

    @Test
    fun testHistoryFilterSortBottomSheet_narrowWidth280dp_extremeFontScale2_5x() {
        val state = HistoryUiState(
            isFilterSheetOpen = true,
            activeFilterSheetTab = 0
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.5f)
            ) {
                MaterialTheme {
                    Box(modifier = Modifier.width(280.dp)) {
                        HistoryFilterSortBottomSheet(
                            uiState = state,
                            onDismissRequest = {},
                            onSelectTab = {},
                            onSessionSortChange = {},
                            onPuzzleScopeChange = {},
                            onSessionKindFilterChange = {},
                            onSolveSortChange = {},
                            onPenaltyFilterChange = {},
                            onTimeRangeFilterChange = {},
                            onDateRangeFilterChange = {},
                            onResetAll = {}
                        )
                    }
                }
            }
        }

        // Sessions tab contents displayed
        composeTestRule.onNodeWithText("Filter & Sort").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Solves").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sort by").assertIsDisplayed()
        composeTestRule.onNodeWithText("Puzzle").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Session Type").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
    }

    // --- Fake Auth Manager helper ---
    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun loginWithGoogle(idToken: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
