package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.data.local.CubeDatabase
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.data.remote.AuthInterceptor
import com.maciekhetman.cubetimer.data.remote.NetworkModule
import com.maciekhetman.cubetimer.model.AuthException
import com.maciekhetman.cubetimer.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Changing the password is `PUT /v1/me/password`. The server answers by revoking every refresh
 * token of the user, this device's included, so on success the client signs in again with the new
 * password (not as a new login: no guest-data adoption). Failures of the change itself leave the
 * session as it was; a failed follow-up sign-in ends the session but still reports success.
 */
@RunWith(RobolectricTestRunner::class)
class AuthManagerChangePasswordTest {

    private lateinit var server: MockWebServer
    private lateinit var database: CubeDatabase
    private lateinit var storage: ThreadSafeFakeTokenStorage
    private lateinit var authManager: AuthManagerImpl
    private val syncTriggerCalls = AtomicInteger(0)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context: Context = ApplicationProvider.getApplicationContext()
        database = CubeDatabase.createInMemory(context)
        storage = ThreadSafeFakeTokenStorage()
        authManager = AuthManagerImpl(
            apiClient = NetworkModule.provideCubeSyncApiClient(
                NetworkModule.provideAuthApiService(
                    server.url("/").toString(),
                    OkHttpClient.Builder().addInterceptor(AuthInterceptor(storage)).build()
                )
            ),
            tokenStorage = storage,
            database = database,
            syncTrigger = { syncTriggerCalls.incrementAndGet() },
            ioDispatcher = Dispatchers.IO,
            authScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            autoInitialize = false
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    private fun sessionBody(accessToken: String, refreshToken: String) = """
        {
            "access_token": "$accessToken",
            "refresh_token": "$refreshToken",
            "token_type": "Bearer",
            "expires_in": 900,
            "user": {"id": "$USER", "email": "cuber@example.com", "user_role": "user", "email_verified": true}
        }
    """.trimIndent()

    private suspend fun signIn() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("access-1", "refresh-1")))
        authManager.login("cuber@example.com", "OldPassword123!")
        server.takeRequest() // the login itself
    }

    private fun errorResponse(status: Int, code: String) =
        MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"internal detail"}}""")

    private fun assertSessionUntouched() {
        assertTrue(authManager.authState.value is AuthState.Authenticated)
        assertEquals("access-1", storage.getAccessToken())
        assertEquals("refresh-1", storage.getRefreshToken())
    }

    @Test
    fun success_signsInAgainWithTheNewPasswordAndStoresTheNewSession() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("access-2", "refresh-2")))

        val result = authManager.changePassword("OldPassword123!", "NewPassword456!")

        assertEquals(AuthResult.Success(Unit), result)

        val change = server.takeRequest()
        assertEquals("PUT", change.method)
        assertEquals("/v1/me/password", change.path)
        assertEquals("Bearer access-1", change.getHeader("Authorization"))

        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/v1/auth/login", login.path)
        val loginBody = Json.parseToJsonElement(login.body.readUtf8()).jsonObject
        assertEquals(setOf("email", "password"), loginBody.keys)
        assertEquals("\"cuber@example.com\"", loginBody.getValue("email").toString())
        assertEquals("\"NewPassword456!\"", loginBody.getValue("password").toString())

        assertEquals("the sign-in, the change and the follow-up sign-in", 3, server.requestCount)
        val state = authManager.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertEquals(USER, (state as AuthState.Authenticated).user.id)
        assertEquals("access-2", storage.getAccessToken())
        assertEquals("refresh-2", storage.getRefreshToken())
        assertEquals(USER, storage.getCachedUser()!!.id)
    }

    @Test
    fun success_isNotANewLoginAndDoesNotAdoptGuestData() = runBlocking {
        signIn()
        database.sessionDao().insertAll(
            listOf(SessionEntity(id = "guest-sess", ownerId = "guest", name = "Guest", startedAt = "2026-09-11T10:00:00.000Z"))
        )
        database.solveDao().insertAll(
            listOf(
                SolveEntity(
                    id = "guest-solve",
                    ownerId = "guest",
                    sessionId = "guest-sess",
                    durationMs = 12000L,
                    solvedAt = "2026-09-11T10:00:00.000Z"
                )
            )
        )
        val triggersBefore = syncTriggerCalls.get()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("access-2", "refresh-2")))

        authManager.changePassword("OldPassword123!", "NewPassword456!")

        assertEquals("guest-solve stays a guest row", "guest", database.solveDao().getSolveById("guest-solve")!!.ownerId)
        assertEquals("guest", database.sessionDao().getSessionById("guest-sess")!!.ownerId)
        assertEquals(0, database.syncOutboxDao().countPending(USER))
        assertEquals("no sync is triggered by a session swap", triggersBefore, syncTriggerCalls.get())
    }

    @Test
    fun requestBody_carriesExactlyCurrentAndNewPassword() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionBody("access-2", "refresh-2")))

        authManager.changePassword("OldPassword123!", "NewPassword456!")

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("current_password", "new_password"), body.keys)
        assertEquals("\"OldPassword123!\"", body.getValue("current_password").toString())
        assertEquals("\"NewPassword456!\"", body.getValue("new_password").toString())
    }

    @Test
    fun wrongCurrentPassword_returnsTheErrorAndLeavesTheSessionUntouched() = runBlocking {
        signIn()
        server.enqueue(errorResponse(401, "invalid_credentials"))

        val result = authManager.changePassword("Wrong-password-1", "NewPassword456!")

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).exception is AuthException.InvalidCredentials)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("no follow-up sign-in after a refused change", 1, server.requestCount - 1)
        assertSessionUntouched()
    }

    @Test
    fun accountWithoutPassword_reportsTheServerCodeAndLeavesTheSessionUntouched() = runBlocking {
        signIn()
        server.enqueue(errorResponse(409, "password_not_set"))

        val result = authManager.changePassword("anything-at-all", "NewPassword456!")

        val error = (result as AuthResult.Error).exception
        assertTrue(error is AuthException.ApiError)
        assertEquals("password_not_set", (error as AuthException.ApiError).errorCode)
        assertSessionUntouched()
    }

    @Test
    fun otherServerRejections_leaveTheSessionUntouched() = runBlocking {
        signIn()
        for ((status, code) in listOf(400 to "invalid_password", 429 to "rate_limited", 500 to "internal_error")) {
            server.enqueue(errorResponse(status, code))

            val result = authManager.changePassword("OldPassword123!", "NewPassword456!")

            assertTrue("HTTP $status: $result", result is AuthResult.Error)
            assertEquals("HTTP $status", "PUT", server.takeRequest().method)
            assertSessionUntouched()
        }
    }

    @Test
    fun networkFailureOfTheChange_returnsNetworkErrorAndLeavesTheSessionUntouched() = runBlocking {
        signIn()
        // Twice: OkHttp retries a request whose connection died before any response arrived.
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }

        val result = authManager.changePassword("OldPassword123!", "NewPassword456!")

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).exception is AuthException.NetworkError)
        assertSessionUntouched()
    }

    @Test
    fun followUpSignInFailure_endsAsGuestWithTokensClearedButStillReportsSuccess() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setResponseCode(204))
        // Twice: OkHttp retries a request whose connection died before any response arrived.
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }

        val result = authManager.changePassword("OldPassword123!", "NewPassword456!")

        assertEquals(AuthResult.Success(Unit), result)
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("/v1/auth/login", server.takeRequest().path)
        assertEquals(AuthState.Guest, authManager.authState.value)
        assertNull(authManager.currentUser)
        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
        assertNull(storage.getCachedUser())
    }

    @Test
    fun followUpSignInRejected_endsAsGuestWithTokensCleared() = runBlocking {
        signIn()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(errorResponse(403, "email_not_verified"))

        val result = authManager.changePassword("OldPassword123!", "NewPassword456!")

        assertEquals(AuthResult.Success(Unit), result)
        assertEquals(AuthState.Guest, authManager.authState.value)
        assertNull(storage.getAccessToken())
        assertNull(storage.getRefreshToken())
    }

    @Test
    fun notSignedIn_returnsAnErrorWithoutCallingTheApi() = runBlocking {
        authManager.initialize() // no stored session: Guest

        val result = authManager.changePassword("OldPassword123!", "NewPassword456!")

        assertTrue(result is AuthResult.Error)
        assertTrue((result as AuthResult.Error).exception is AuthException.Unauthorized)
        assertEquals(0, server.requestCount)
        assertEquals(AuthState.Guest, authManager.authState.value)
    }

    private companion object {
        const val USER = "user-1"
    }
}
