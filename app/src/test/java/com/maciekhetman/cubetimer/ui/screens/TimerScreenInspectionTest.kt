package com.maciekhetman.cubetimer.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.SettingsRepository
import com.maciekhetman.cubetimer.data.SolvesRepository
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.mapper.toEntity
import com.maciekhetman.cubetimer.data.local.migration.DataStoreMigration
import com.maciekhetman.cubetimer.data.session.SessionManager
import com.maciekhetman.cubetimer.data.settingsDataStore
import com.maciekhetman.cubetimer.data.solvesDataStore
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.TimerState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.viewmodel.TimerViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The inspection UI on the real [TimerScreen]: the "Cancel" button must not double as a timer press.
 * Time is the real (Robolectric) clock; the test only looks at states that don't depend on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerScreenInspectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var database: CubeDatabase
    private lateinit var viewModel: TimerViewModel

    private val session = Session(
        id = "inspection-ui-session",
        ownerId = "guest",
        name = "30 sep 2026 afternoon",
        event = Mode.CUBE_3x3,
        startedAt = "2026-09-30T12:00:00.000Z"
    )

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        runBlocking {
            application.settingsDataStore.edit { it.clear() }
            application.solvesDataStore.edit { it.clear() }
        }
        database = CubeDatabase.createInMemory(application)
        runBlocking { database.sessionDao().upsert(session.toEntity()) }
        val settingsRepository = SettingsRepository(application)
        viewModel = TimerViewModel(
            application = application,
            repository = SolvesRepository(
                context = application,
                solveDao = database.solveDao(),
                sessionDao = database.sessionDao(),
                syncOutboxDao = database.syncOutboxDao(),
                database = database
            ),
            settingsRepository = settingsRepository,
            sessionManager = FixedSessionManager(session),
            authManager = GuestAuthManager()
        )
        // The legacy DataStore migration writes its flag on a real thread; a test write racing with it
        // can fail the DataStore file rename on Windows.
        repeat(500) {
            val migrated = runBlocking { application.settingsDataStore.data.first() }[DataStoreMigration.DATASTORE_SOLVES_MIGRATED_KEY]
            if (migrated == true) return@repeat
            Thread.sleep(10)
        }
        runBlocking { settingsRepository.setInspectionEnabled(true) }
    }

    @After
    fun tearDown() {
        // Leaves inspection (its ticker) before the ViewModel is abandoned.
        composeTestRule.runOnUiThread { viewModel.cancelInspection() }
        database.close()
    }

    private fun stateNow(): TimerState = viewModel.timerState.value

    @Test
    fun cancelButton_returnsToIdleWithoutRegisteringATimerPress() {
        composeTestRule.setContent { MaterialTheme { TimerScreen(viewModel, Mode.CUBE_3x3, onModeSelected = {}) } }
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.inspectionEnabled.value }

        // A press on the timer area starts inspection...
        composeTestRule.onNodeWithContentDescription("Tap to start inspection").performTouchInput { click() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { stateNow() is TimerState.Inspecting }
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()

        // ...and a press on it during inspection begins a hold (so the check below can tell a leak).
        val timerArea = composeTestRule.onNodeWithContentDescription("Hold and release to start timer")
        timerArea.performTouchInput { down(Offset(8f, 8f)) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { (stateNow() as? TimerState.Inspecting)?.holdProgress != null }
        timerArea.performTouchInput { up() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { (stateNow() as? TimerState.Inspecting)?.holdProgress == null }
        assertTrue(stateNow() is TimerState.Inspecting)

        // Touching Cancel does not begin a hold on the timer area behind it.
        val cancel = composeTestRule.onNodeWithText("Cancel")
        cancel.performTouchInput { down(center) }
        composeTestRule.waitForIdle()
        assertNull((stateNow() as TimerState.Inspecting).holdProgress)
        cancel.performTouchInput { up() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { stateNow() is TimerState.Idle }

        assertEquals(TimerState.Idle, stateNow())
        composeTestRule.onNodeWithContentDescription("Tap to start inspection").assertIsDisplayed()
    }

    @Test
    fun inspectionHint_followsTheStartGesture() {
        composeTestRule.setContent { MaterialTheme { TimerScreen(viewModel, Mode.CUBE_3x3, onModeSelected = {}) } }
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.inspectionEnabled.value }

        composeTestRule.onNodeWithText("Tap to start inspection").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Tap to start inspection").performTouchInput { click() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { stateNow() is TimerState.Inspecting }

        composeTestRule.onNodeWithText("Hold and release to start").assertIsDisplayed()
        composeTestRule.onNodeWithText("15").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { stateNow() is TimerState.Idle }
    }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FixedSessionManager(private val session: Session) : SessionManager {
        override fun getActiveSessionFlow(ownerId: String, mode: Mode): Flow<Session?> = MutableStateFlow(session)
        override suspend fun getOrCreateActiveSession(ownerId: String, mode: Mode, solveTimestamp: Long?): Session = session
    }

    private class GuestAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
