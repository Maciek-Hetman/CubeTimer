package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared by the refresh-coordination tests. Like the real [EncryptedTokenStorage] it is safe to
 * call from many threads and its access token is a plain in-memory value that starts out absent.
 */
internal class ThreadSafeFakeTokenStorage : TokenStorage {
    private val lock = Any()
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var cachedUser: User? = null
    private val accessTokenState = MutableStateFlow<String?>(null)

    val clearAuthDataCalls = AtomicInteger(0)

    override val accessTokenFlow: StateFlow<String?> = accessTokenState

    /** Puts the storage into a given state, e.g. a cold start: refresh token and identity, no access token. */
    fun seed(accessToken: String?, refreshToken: String?, user: User?) {
        setAccessToken(accessToken)
        synchronized(lock) {
            this.refreshToken = refreshToken
            this.cachedUser = user
        }
    }

    override fun getAccessToken(): String? = synchronized(lock) { accessToken }
    override fun setAccessToken(token: String?) {
        synchronized(lock) { accessToken = token }
        accessTokenState.value = token
    }
    override fun getRefreshToken(): String? = synchronized(lock) { refreshToken }
    override fun getUserId(): String? = synchronized(lock) { cachedUser?.id }
    override fun getUserEmail(): String? = synchronized(lock) { cachedUser?.email }
    override fun getUserRole(): String? = synchronized(lock) {
        cachedUser?.let { if (it.userRole == UserRole.ADMIN) "admin" else "user" }
    }
    override fun isUserEmailVerified(): Boolean = synchronized(lock) { cachedUser?.emailVerified ?: false }
    override fun getDisplayName(): String? = synchronized(lock) { cachedUser?.displayName }
    override fun getCachedUser(): User? = synchronized(lock) { cachedUser }

    override fun saveAuthSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        userEmail: String,
        userRole: String,
        emailVerified: Boolean,
        displayName: String?
    ) {
        synchronized(lock) {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            this.cachedUser = User(
                id = userId,
                email = userEmail,
                displayName = displayName,
                emailVerified = emailVerified,
                userRole = UserRole.fromString(userRole)
            )
        }
        accessTokenState.value = accessToken
    }

    override fun getDeviceId(): String = "device-refresh-tests"

    override fun clearAuthData() {
        clearAuthDataCalls.incrementAndGet()
        synchronized(lock) {
            accessToken = null
            refreshToken = null
            cachedUser = null
        }
        accessTokenState.value = null
    }

    override fun clearAll() = clearAuthData()
}

/**
 * A CubeSync stand-in that enforces refresh-token rotation the way the real server does: every
 * `/v1/auth/refresh` invalidates the token it was given and issues the next one, and presenting an
 * already-rotated token is `refresh_token_reused`. `/v1/data` accepts only the newest access token.
 *
 * Refresh responses can be held back ([holdRefreshes] / [releaseRefreshes]) to keep one refresh
 * in flight while the test starts a competing one.
 */
internal class RotatingRefreshBackend : Dispatcher() {
    private val generation = AtomicInteger(0)

    @Volatile var validRefreshToken = "refresh-0"
    @Volatile var validAccessToken = "access-0-never-issued-to-the-client"

    val refreshCalls = AtomicInteger(0)
    val reuseRejections = AtomicInteger(0)
    val unauthorizedDataCalls = AtomicInteger(0)

    @Volatile private var refreshArrived = CountDownLatch(1)
    @Volatile private var refreshGate = CountDownLatch(0)

    /** From now on refresh requests are received but not answered until [releaseRefreshes]. */
    fun holdRefreshes() {
        refreshArrived = CountDownLatch(1)
        refreshGate = CountDownLatch(1)
    }

    fun releaseRefreshes() = refreshGate.countDown()

    /** Blocks until a refresh request has reached the server. */
    fun awaitRefreshArrived(): Boolean = refreshArrived.await(10, TimeUnit.SECONDS)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path.startsWith("/v1/auth/refresh") -> refresh(request)
            path.startsWith("/v1/data") -> data(request)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun refresh(request: RecordedRequest): MockResponse {
        val presented = REFRESH_TOKEN_FIELD.find(request.body.readUtf8())?.groupValues?.get(1)
        refreshCalls.incrementAndGet()
        refreshArrived.countDown()
        refreshGate.await(10, TimeUnit.SECONDS)

        synchronized(this) {
            if (presented != validRefreshToken) {
                reuseRejections.incrementAndGet()
                return MockResponse().setResponseCode(409)
                    .setBody("""{"error":{"code":"refresh_token_reused","message":"Token family revoked"}}""")
            }
            val n = generation.incrementAndGet()
            validRefreshToken = "refresh-$n"
            validAccessToken = "access-$n"
            return MockResponse().setResponseCode(200).setBody(
                """
                {
                    "access_token": "access-$n",
                    "refresh_token": "refresh-$n",
                    "token_type": "Bearer",
                    "expires_in": 900,
                    "user": {
                        "id": "$USER_ID",
                        "email": "$USER_EMAIL",
                        "user_role": "user",
                        "email_verified": true
                    }
                }
                """.trimIndent()
            )
        }
    }

    private fun data(request: RecordedRequest): MockResponse {
        return if (request.getHeader("Authorization") == "Bearer $validAccessToken") {
            MockResponse().setResponseCode(200).setBody("""{"status":"ok"}""")
        } else {
            unauthorizedDataCalls.incrementAndGet()
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized"}}""")
        }
    }

    companion object {
        const val USER_ID = "user-1"
        const val USER_EMAIL = "cuber@example.com"

        private val REFRESH_TOKEN_FIELD = Regex(""""refresh_token"\s*:\s*"([^"]+)"""")

        val cachedUser = User(
            id = USER_ID,
            email = USER_EMAIL,
            displayName = null,
            emailVerified = true,
            userRole = UserRole.USER
        )
    }
}
