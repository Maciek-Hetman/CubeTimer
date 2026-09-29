package com.maciekhetman.cubetimer.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.maciekhetman.cubetimer.data.auth.AuthManager
import com.maciekhetman.cubetimer.data.auth.AuthResult
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.ConflictEntity
import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.SyncUiState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SyncUiState.conflictCount] mirrors the signed-in owner's unresolved rows in `sync_conflicts`,
 * stays reactive, and is always 0 for guests and for a manager without a database.
 */
@RunWith(RobolectricTestRunner::class)
class SyncStateManagerConflictCountTest {

    private lateinit var context: Context
    private lateinit var database: CubeDatabase
    private lateinit var authManager: FakeAuthManager
    private lateinit var syncStateManager: SyncStateManager

    private val user = User(id = "usr_1", email = "test@example.com")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        authManager = FakeAuthManager()
        syncStateManager = SyncStateManager(
            context = context,
            database = database,
            authManager = authManager
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun conflict(id: String, ownerId: String = user.id, resolved: Boolean = false) = ConflictEntity(
        conflictId = id,
        ownerId = ownerId,
        mutationId = "m_$id",
        entityType = "solve",
        entityId = "solve_$id",
        createdAt = "2026-08-30T10:05:00.000Z",
        resolved = resolved,
        resolvedAt = if (resolved) "2026-08-30T11:00:00.000Z" else null
    )

    private suspend fun ReceiveTurbine<SyncUiState>.awaitUntil(predicate: (SyncUiState) -> Boolean): SyncUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    @Test
    fun guestHasNoConflictCountEvenWithRowsInTheTable() = runTest {
        database.conflictDao().insert(conflict("c1", ownerId = "guest"))

        syncStateManager.syncUiState.test {
            val item = awaitItem()
            assertTrue(item.isGuest)
            assertEquals(0, item.conflictCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun authenticatedUserCountsOnlyOwnUnresolvedConflicts() = runTest {
        database.conflictDao().insertAll(
            listOf(
                conflict("c1"),
                conflict("c2"),
                conflict("c3", resolved = true),
                conflict("c4", ownerId = "someone_else")
            )
        )

        syncStateManager.syncUiState.test {
            awaitItem() // initial guest
            authManager.setAuthState(AuthState.Authenticated(user))

            val item = awaitUntil { !it.isGuest && it.conflictCount == 2 }
            assertEquals(2, item.conflictCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun adminCountsOwnUnresolvedConflicts() = runTest {
        database.conflictDao().insertAll(listOf(conflict("c1"), conflict("c2", ownerId = "someone_else")))

        syncStateManager.syncUiState.test {
            awaitItem() // initial guest
            authManager.setAuthState(AuthState.Admin(user.copy(userRole = UserRole.ADMIN)))

            val item = awaitUntil { !it.isGuest && it.conflictCount == 1 }
            assertEquals(1, item.conflictCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun countFollowsNewAndResolvedConflicts() = runTest {
        authManager.setAuthState(AuthState.Authenticated(user))

        syncStateManager.syncUiState.test {
            val none = awaitUntil { !it.isGuest }
            assertEquals(0, none.conflictCount)

            database.conflictDao().insertAll(listOf(conflict("c1"), conflict("c2")))
            assertEquals(2, awaitUntil { it.conflictCount == 2 }.conflictCount)

            database.conflictDao().resolveConflict("c1", "2026-08-30T12:00:00.000Z")
            assertEquals(1, awaitUntil { it.conflictCount == 1 }.conflictCount)

            database.conflictDao().resolveConflict("c2", "2026-08-30T12:00:01.000Z")
            assertEquals(0, awaitUntil { it.conflictCount == 0 }.conflictCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun signingOutResetsTheCountToGuestZero() = runTest {
        database.conflictDao().insert(conflict("c1"))
        authManager.setAuthState(AuthState.Authenticated(user))

        syncStateManager.syncUiState.test {
            awaitUntil { !it.isGuest && it.conflictCount == 1 }

            authManager.setAuthState(AuthState.Guest)
            val guest = awaitUntil { it.isGuest }
            assertEquals(0, guest.conflictCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun managerWithoutDatabaseReportsZeroConflicts() = runTest {
        val bare = SyncStateManager()

        bare.syncUiState.test {
            val item = awaitItem()
            assertEquals(0, item.conflictCount)
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
