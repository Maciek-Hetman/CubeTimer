package com.maciekhetman.cubetimer.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.data.sync.SyncEngine
import com.maciekhetman.cubetimer.data.sync.SyncResult
import com.maciekhetman.cubetimer.data.sync.SyncStateManager
import com.maciekhetman.cubetimer.data.sync.SyncStatus
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConflictViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var application: Application
    private lateinit var authManager: FakeAuthManager
    private lateinit var syncEngine: FakeSyncEngine
    private var syncTriggers = 0

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        authManager = FakeAuthManager()
        syncEngine = FakeSyncEngine()
        syncTriggers = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ConflictViewModel(
        application = application,
        syncEngine = syncEngine,
        authManager = authManager,
        onResolved = { syncTriggers++ },
        ioDispatcher = testDispatcher,
        mappingDispatcher = testDispatcher
    )

    private fun solveConflict(id: String, ownerId: String = "usr_1") = ConflictEntity(
        conflictId = id,
        ownerId = ownerId,
        mutationId = "m_$id",
        entityType = "solve",
        entityId = "solve_$id",
        localPayloadJson = """{"id":"solve_$id","duration_ms":12340,"penalty":"none","solved_at":"2026-08-30T10:00:00.000Z","scramble":"R U","event":"3x3"}""",
        serverPayloadJson = """{"id":"solve_$id","duration_ms":12340,"penalty":"dnf","solved_at":"2026-08-30T10:00:00.000Z","event":"3x3","version":3}""",
        createdAt = "2026-08-30T10:05:00.000Z"
    )

    @Test
    fun guestSeesNoConflicts() = testScope.runTest {
        syncEngine.conflicts.value = listOf(solveConflict("c1", ownerId = "guest"))
        val vm = createViewModel()
        backgroundScope.launch { vm.conflicts.collect {} }
        advanceUntilIdle()

        assertTrue(vm.conflicts.value.isEmpty())
        assertTrue(syncEngine.observedOwners.isEmpty())
    }

    @Test
    fun authenticatedUserSeesMappedConflictsForOwnId() = testScope.runTest {
        syncEngine.conflicts.value = listOf(solveConflict("c1"), solveConflict("c2"), solveConflict("c3", ownerId = "other"))
        authManager.setAuthState(AuthState.Authenticated(User(id = "usr_1", email = "a@b.c")))
        val vm = createViewModel()
        backgroundScope.launch { vm.conflicts.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("usr_1"), syncEngine.observedOwners)
        val list = vm.conflicts.value
        assertEquals(listOf("c1", "c2"), list.map { it.id })
        assertEquals("Solve", list[0].title)
        assertEquals("3x3 · 12.34", list[0].local.lines.first())
        assertEquals("3x3 · DNF (12.34)", list[0].server.lines.first())

        // Signing out clears the list.
        authManager.setAuthState(AuthState.Guest)
        advanceUntilIdle()
        assertTrue(vm.conflicts.value.isEmpty())
    }

    @Test
    fun adminSeesMappedConflictsForOwnId() = testScope.runTest {
        syncEngine.conflicts.value = listOf(solveConflict("c1"), solveConflict("c2", ownerId = "other"))
        authManager.setAuthState(
            AuthState.Admin(User(id = "usr_1", email = "admin@b.c", userRole = UserRole.ADMIN))
        )
        val vm = createViewModel()
        backgroundScope.launch { vm.conflicts.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("usr_1"), syncEngine.observedOwners)
        assertEquals(listOf("c1"), vm.conflicts.value.map { it.id })

        // Signing out clears the list.
        authManager.setAuthState(AuthState.Guest)
        advanceUntilIdle()
        assertTrue(vm.conflicts.value.isEmpty())
    }

    @Test
    fun loadingStateShowsNoConflicts() = testScope.runTest {
        syncEngine.conflicts.value = listOf(solveConflict("c1"))
        authManager.setAuthState(AuthState.Loading)
        val vm = createViewModel()
        backgroundScope.launch { vm.conflicts.collect {} }
        advanceUntilIdle()

        assertTrue(vm.conflicts.value.isEmpty())
        assertTrue(syncEngine.observedOwners.isEmpty())
    }

    @Test
    fun keepLocalResolvesAndTriggersSync() = testScope.runTest {
        val vm = createViewModel()
        vm.keepLocal("c1")
        assertTrue("c1" in vm.resolvingIds.value)
        advanceUntilIdle()

        assertEquals(listOf("c1"), syncEngine.keepLocalCalls)
        assertTrue(syncEngine.keepServerCalls.isEmpty())
        assertEquals(1, syncTriggers)
        assertTrue(vm.resolvingIds.value.isEmpty())
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun keepServerResolvesAndTriggersSync() = testScope.runTest {
        val vm = createViewModel()
        vm.keepServer("c2")
        advanceUntilIdle()

        assertEquals(listOf("c2"), syncEngine.keepServerCalls)
        assertTrue(syncEngine.keepLocalCalls.isEmpty())
        assertEquals(1, syncTriggers)
    }

    @Test
    fun duplicateTapWhileResolvingIsIgnored() = testScope.runTest {
        val vm = createViewModel()
        vm.keepLocal("c1")
        vm.keepServer("c1")
        advanceUntilIdle()

        assertEquals(listOf("c1"), syncEngine.keepLocalCalls)
        assertTrue(syncEngine.keepServerCalls.isEmpty())
        assertEquals(1, syncTriggers)
    }

    @Test
    fun failedResolutionShowsErrorAndDoesNotTriggerSync() = testScope.runTest {
        syncEngine.resolveResult = false
        val vm = createViewModel()
        vm.keepServer("c1")
        advanceUntilIdle()

        assertEquals(0, syncTriggers)
        assertNotNull(vm.errorMessage.value)
        vm.clearError()
        assertNull(vm.errorMessage.value)

        syncEngine.resolveResult = true
        syncEngine.throwOnResolve = true
        vm.keepLocal("c1")
        advanceUntilIdle()
        assertEquals(0, syncTriggers)
        assertNotNull(vm.errorMessage.value)
        assertTrue(vm.resolvingIds.value.isEmpty())
    }

    private class FakeSyncEngine : SyncEngine {
        val conflicts = MutableStateFlow<List<ConflictEntity>>(emptyList())
        val observedOwners = mutableListOf<String>()
        val keepLocalCalls = mutableListOf<String>()
        val keepServerCalls = mutableListOf<String>()
        var resolveResult = true
        var throwOnResolve = false

        override val syncStatus: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.SYNCED)
        override val lastSyncedAt: StateFlow<Long?> = MutableStateFlow(null)
        override val isSyncing: StateFlow<Boolean> = MutableStateFlow(false)
        override val stateManager: SyncStateManager = SyncStateManager()

        override fun observePendingMutationsCount(ownerId: String): Flow<Int> = flowOf(0)

        override fun observeUnresolvedConflicts(ownerId: String): Flow<List<ConflictEntity>> {
            observedOwners += ownerId
            return conflicts.map { list -> list.filter { it.ownerId == ownerId && !it.resolved } }
        }

        override suspend fun sync(ownerId: String?): SyncResult = SyncResult.Success()

        override suspend fun runSnapshotBootstrap(ownerId: String): Long = 0L

        override suspend fun resolveConflictKeepServer(conflictId: String): Boolean {
            if (throwOnResolve) throw IllegalStateException("boom")
            keepServerCalls += conflictId
            return resolveResult
        }

        override suspend fun resolveConflictKeepLocal(conflictId: String): Boolean {
            if (throwOnResolve) throw IllegalStateException("boom")
            keepLocalCalls += conflictId
            return resolveResult
        }
    }

    private class FakeAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        fun setAuthState(state: AuthState) {
            _authState.value = state
            currentUser = (state as? AuthState.Authenticated)?.user ?: (state as? AuthState.Admin)?.user
        }

        override suspend fun initialize() = Unit
        override suspend fun register(email: String, password: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun login(email: String, password: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun loginWithGoogle(idToken: String, clientId: String, nonce: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun verifyEmail(token: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun resendVerificationEmail(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun refreshSession(): AuthResult<User> = AuthResult.Success(User(id = "u1", email = "u@test.com"))
        override suspend fun logout(): AuthResult<Unit> = AuthResult.Success(Unit)
        override suspend fun adoptGuestData(userId: String) = Unit
    }
}
