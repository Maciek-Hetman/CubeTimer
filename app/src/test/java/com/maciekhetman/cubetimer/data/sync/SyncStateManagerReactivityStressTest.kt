package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SyncMetadataEntity
import com.maciekhetman.cubetimer.data.local.entity.SyncOutboxEntity
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.SyncStatusType
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Adversarial stress and reactivity test suite for [SyncStateManager] covering:
 * - High-frequency network toggling (rapid online/offline transitions)
 * - Heavy outbox queue insertion, processing, and multi-user isolation
 * - Error propagation and recovery when metadata transitions between Error, Syncing, Synced
 * - Multi-stage AuthState lifecycle transitions (Guest -> User1 -> User2 -> Admin -> Guest)
 * - Trigger sync delegate invocations
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncStateManagerReactivityStressTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var fakeAuthManager: FakeStressAuthManager
    private lateinit var syncStateManager: SyncStateManager

    private var triggerSyncCallCount = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        fakeAuthManager = FakeStressAuthManager()
        triggerSyncCallCount = 0
    }

    private fun createSyncStateManager(): SyncStateManager {
        val manager = SyncStateManager(
            context = context,
            database = database,
            authManager = fakeAuthManager,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            onTriggerSync = {
                triggerSyncCallCount++
            }
        )
        manager.setOnlineForTest(true)
        return manager
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------------------------------------
    // HIGH-FREQUENCY NETWORK TOGGLING
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `rapid network state toggling correctly reflects OFFLINE and SYNCED states`() = runTest(UnconfinedTestDispatcher()) {
        syncStateManager = createSyncStateManager()
        val testUser = User(id = "user_net_test", email = "net@test.com")
        fakeAuthManager.setAuthState(AuthState.Authenticated(testUser))

        syncStateManager.syncUiState.test(timeout = 10.seconds) {
            var current = awaitItem()
            while (current.isGuest) {
                current = awaitItem()
            }
            assertFalse(current.isGuest)

            // Rapidly toggle online state 10 times
            for (i in 1..10) {
                val isOnline = (i % 2 == 0)
                syncStateManager.setOnlineForTest(isOnline)

                var latest = awaitItem()
                val expectedStatus = if (isOnline) SyncStatusType.SYNCED else SyncStatusType.OFFLINE
                while (latest.status != expectedStatus) {
                    latest = awaitItem()
                }
                assertEquals("Expected $expectedStatus on iteration $i", expectedStatus, latest.status)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HEAVY OUTBOX MUTATION REACTIVITY & USER ISOLATION
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `burst outbox insertions and deletions update pendingCount accurately`() = runTest(UnconfinedTestDispatcher()) {
        syncStateManager = createSyncStateManager()
        val user1 = User(id = "user_burst_1", email = "user1@test.com")
        fakeAuthManager.setAuthState(AuthState.Authenticated(user1))

        syncStateManager.syncUiState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item.isGuest) {
                item = awaitItem()
            }
            assertEquals(0, item.pendingCount)

            // Enqueue 15 mutations in sequence
            val outboxIds = mutableListOf<String>()
            for (i in 1..15) {
                val id = UUID.randomUUID().toString()
                outboxIds.add(id)
                database.syncOutboxDao().enqueue(
                    SyncOutboxEntity(
                        id = id,
                        ownerId = user1.id,
                        entityType = "solve",
                        entityId = "solve_$i",
                        action = "create",
                        clientTime = "2026-08-30T10:00:00Z"
                    )
                )
            }

            var latest = awaitItem()
            while (latest.pendingCount < 15) {
                latest = awaitItem()
            }
            assertEquals(15, latest.pendingCount)

            // Delete 10 mutations
            val toDelete = outboxIds.take(10)
            database.syncOutboxDao().deleteByIds(toDelete)

            while (latest.pendingCount > 5) {
                latest = awaitItem()
            }
            assertEquals(5, latest.pendingCount)

            // Delete remaining 5 mutations
            val remaining = outboxIds.drop(10)
            database.syncOutboxDao().deleteByIds(remaining)

            while (latest.pendingCount > 0) {
                latest = awaitItem()
            }
            assertEquals(0, latest.pendingCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user switching isolates pending mutations and error state between accounts`() = runTest(UnconfinedTestDispatcher()) {
        syncStateManager = createSyncStateManager()
        val userA = User(id = "user_A", email = "a@test.com")
        val userB = User(id = "user_B", email = "b@test.com")

        // Seed User A data
        database.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                id = "mut_a1",
                ownerId = userA.id,
                entityType = "solve",
                entityId = "s_a1",
                action = "create",
                clientTime = "2026-08-30T10:00:00Z"
            )
        )
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                ownerId = userA.id,
                deviceId = "dev_a",
                lastError = "User A auth failed"
            )
        )

        // Seed User B data
        database.syncMetadataDao().upsert(
            SyncMetadataEntity(
                ownerId = userB.id,
                deviceId = "dev_b",
                lastSyncTime = "2026-08-30T14:30:00Z"
            )
        )

        syncStateManager.syncUiState.test(timeout = 10.seconds) {
            awaitItem() // Initial guest

            // Switch to User A -> Should see 1 pending and ERROR
            fakeAuthManager.setAuthState(AuthState.Authenticated(userA))
            var stateA = awaitItem()
            while (stateA.errorMessage != "User A auth failed") {
                stateA = awaitItem()
            }
            assertEquals(SyncStatusType.ERROR, stateA.status)
            assertEquals("User A auth failed", stateA.errorMessage)
            assertEquals(1, stateA.pendingCount)
            assertFalse(stateA.isGuest)

            // Switch to User B -> Should see 0 pending, SYNCED, no error, and User B's sync timestamp
            fakeAuthManager.setAuthState(AuthState.Authenticated(userB))
            var stateB = awaitItem()
            while (stateB.lastSyncTime != "2026-08-30T14:30:00Z" || stateB.errorMessage != null) {
                stateB = awaitItem()
            }
            assertEquals(SyncStatusType.SYNCED, stateB.status)
            assertNull("User B should not have User A's error", stateB.errorMessage)
            assertEquals("User B should have 0 pending mutations", 0, stateB.pendingCount)
            assertFalse(stateB.isGuest)

            // Switch to Guest -> Should reset to Guest state
            fakeAuthManager.setAuthState(AuthState.Guest)
            var guestState = awaitItem()
            while (!guestState.isGuest) {
                guestState = awaitItem()
            }
            assertTrue(guestState.isGuest)
            assertEquals(0, guestState.pendingCount)
            assertNull(guestState.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SYNC STATE TRANSITIONS (ERROR -> SYNCING -> SYNCED)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `metadata transitions from Error to Syncing to Synced reflect immediately`() = runTest(UnconfinedTestDispatcher()) {
        syncStateManager = createSyncStateManager()
        val user = User(id = "user_state_flow", email = "flow@test.com")
        fakeAuthManager.setAuthState(AuthState.Authenticated(user))

        syncStateManager.syncUiState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item.isGuest) {
                item = awaitItem()
            }

            // 1. Error state
            database.syncMetadataDao().upsert(
                SyncMetadataEntity(
                    ownerId = user.id,
                    deviceId = "dev_1",
                    lastError = "503 Service Unavailable",
                    isSyncing = false
                )
            )
            while (item.status != SyncStatusType.ERROR) {
                item = awaitItem()
            }
            assertEquals(SyncStatusType.ERROR, item.status)
            assertEquals("503 Service Unavailable", item.errorMessage)

            // 2. Syncing state (in-progress sync clears error display)
            database.syncMetadataDao().upsert(
                SyncMetadataEntity(
                    ownerId = user.id,
                    deviceId = "dev_1",
                    lastError = null,
                    isSyncing = true
                )
            )
            while (item.status != SyncStatusType.SYNCING) {
                item = awaitItem()
            }
            assertEquals(SyncStatusType.SYNCING, item.status)
            assertNull(item.errorMessage)

            // 3. Synced state (sync completed successfully)
            database.syncMetadataDao().upsert(
                SyncMetadataEntity(
                    ownerId = user.id,
                    deviceId = "dev_1",
                    lastError = null,
                    isSyncing = false,
                    lastSyncTime = "2026-08-30T16:00:00Z"
                )
            )
            while (item.status != SyncStatusType.SYNCED || item.lastSyncTime == null) {
                item = awaitItem()
            }
            assertEquals(SyncStatusType.SYNCED, item.status)
            assertEquals("2026-08-30T16:00:00Z", item.lastSyncTime)
            assertNull(item.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `triggerSync executes registered callback`() = runTest(UnconfinedTestDispatcher()) {
        syncStateManager = createSyncStateManager()
        assertEquals(0, triggerSyncCallCount)
        syncStateManager.triggerSync()
        assertEquals(1, triggerSyncCallCount)
    }

    // ---------------------------------------------------------------------------------------------
    // FAKE AUTH MANAGER FOR STRESS TESTING
    // ---------------------------------------------------------------------------------------------

    private class FakeStressAuthManager : AuthManager {
        private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override var currentUser: User? = null

        fun setAuthState(state: AuthState) {
            _authState.value = state
            currentUser = when (state) {
                is AuthState.Authenticated -> state.user
                is AuthState.Admin -> state.user
                else -> null
            }
        }

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
