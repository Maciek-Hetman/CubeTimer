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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncStateManagerTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var fakeAuthManager: FakeAuthManager
    private lateinit var syncStateManager: SyncStateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        fakeAuthManager = FakeAuthManager()

        syncStateManager = SyncStateManager(
            context = context,
            database = database,
            authManager = fakeAuthManager
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInitialGuestState() = runTest {
        syncStateManager.syncUiState.test {
            val item = awaitItem()
            assertTrue(item.isGuest)
            assertEquals(0, item.pendingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testAuthenticatedUserWithPendingOutboxShowsPendingCount() = runTest {
        syncStateManager.syncUiState.test {
            // Initial guest state
            val item1 = awaitItem()
            assertTrue(item1.isGuest)

            // Authenticate user
            fakeAuthManager.setAuthState(AuthState.Authenticated(User(id = "usr_1", email = "test@example.com")))

            // Wait for authenticated state emission
            var authItem = awaitItem()
            while (authItem.isGuest) {
                authItem = awaitItem()
            }
            assertFalse(authItem.isGuest)

            // Insert pending outbox mutation
            val outboxItem = SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                ownerId = "usr_1",
                entityType = "solve",
                entityId = "s1",
                action = "create",
                clientTime = "2026-08-30T10:00:00Z"
            )
            database.syncOutboxDao().enqueue(outboxItem)

            var countItem = awaitItem()
            while (countItem.pendingCount == 0) {
                countItem = awaitItem()
            }
            assertEquals(1, countItem.pendingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testSyncErrorMetadataTriggersErrorStatus() = runTest {
        syncStateManager.syncUiState.test {
            awaitItem() // Initial guest

            fakeAuthManager.setAuthState(AuthState.Authenticated(User(id = "usr_1", email = "test@example.com")))
            
            val errorMetadata = SyncMetadataEntity(
                ownerId = "usr_1",
                deviceId = "dev_1",
                lastError = "Server 500 internal error"
            )
            database.syncMetadataDao().upsert(errorMetadata)

            var errorItem = awaitItem()
            while (errorItem.status != SyncStatusType.ERROR) {
                errorItem = awaitItem()
            }
            assertEquals(SyncStatusType.ERROR, errorItem.status)
            assertEquals("Server 500 internal error", errorItem.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testSyncMetadataTimestampUpdatesLastSyncTime() = runTest {
        syncStateManager.syncUiState.test {
            awaitItem() // Initial guest

            fakeAuthManager.setAuthState(AuthState.Authenticated(User(id = "usr_1", email = "test@example.com")))

            val metadata = SyncMetadataEntity(
                ownerId = "usr_1",
                deviceId = "dev_1",
                lastSyncTime = "2026-08-30T12:00:00Z"
            )
            database.syncMetadataDao().upsert(metadata)

            var syncItem = awaitItem()
            while (syncItem.lastSyncTime == null) {
                syncItem = awaitItem()
            }
            assertEquals("2026-08-30T12:00:00Z", syncItem.lastSyncTime)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testOfflineStateWhenNetworkUnavailable() = runTest {
        syncStateManager.syncUiState.test {
            awaitItem() // Initial guest

            fakeAuthManager.setAuthState(AuthState.Authenticated(User(id = "usr_1", email = "test@example.com")))

            syncStateManager.setOnlineForTest(false)

            var offlineItem = awaitItem()
            while (offlineItem.status != SyncStatusType.OFFLINE) {
                offlineItem = awaitItem()
            }
            assertEquals(SyncStatusType.OFFLINE, offlineItem.status)
            cancelAndIgnoreRemainingEvents()
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
