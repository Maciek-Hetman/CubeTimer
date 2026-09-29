package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.RotatingRefreshBackend
import com.maciekhetman.cubetimer.data.auth.ThreadSafeFakeTokenStorage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TokenRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: ThreadSafeFakeTokenStorage
    private lateinit var refresher: TokenRefresher

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        storage = ThreadSafeFakeTokenStorage()
        refresher = TokenRefresher(storage, server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val successBody = """
        {"access_token":"access-new","refresh_token":"refresh-new","token_type":"Bearer","expires_in":900,
         "user":{"id":"user-1","email":"cuber@example.com","user_role":"admin","email_verified":true,
                 "display_name":"Cuber"}}
    """.trimIndent()

    @Test
    fun refresh_exchangesTheStoredRefreshTokenAndPersistsTheNewSession() {
        storage.seed(accessToken = "expired", refreshToken = "refresh-old", user = null)
        server.enqueue(MockResponse().setResponseCode(200).setBody(successBody))

        val result = refresher.refresh(staleAccessToken = "expired")

        assertEquals("access-new", (result as RefreshResult.Refreshed).session.accessToken)
        assertEquals("access-new", storage.getAccessToken())
        assertEquals("refresh-new", storage.getRefreshToken())
        assertEquals("user-1", storage.getUserId())
        assertEquals("admin", storage.getUserRole())
        assertEquals("Cuber", storage.getDisplayName())

        val request = server.takeRequest()
        assertEquals("/v1/auth/refresh", request.path)
        assertTrue(request.body.readUtf8().contains("refresh-old"))
        assertEquals("device-refresh-tests", request.getHeader("X-Device-Id"))
        assertEquals("1", request.getHeader("X-Sync-Protocol"))
    }

    @Test
    fun refresh_whenTheAccessTokenAlreadyChanged_makesNoRequest() {
        // Somebody else rotated the session after this caller saw "expired" fail.
        storage.seed(accessToken = "already-rotated", refreshToken = "refresh-rotated", user = null)

        val result = refresher.refresh(staleAccessToken = "expired")

        assertEquals(RefreshResult.AlreadyRefreshed("already-rotated"), result)
        assertEquals(0, server.requestCount)
        assertEquals("refresh-rotated", storage.getRefreshToken())
    }

    @Test
    fun refresh_fromColdStart_treatsAnyStoredAccessTokenAsAlreadyRefreshed() {
        // Cold start callers have no access token of their own (stale = null).
        storage.seed(accessToken = "issued-meanwhile", refreshToken = "refresh-rotated", user = null)

        assertEquals(RefreshResult.AlreadyRefreshed("issued-meanwhile"), refresher.refresh(staleAccessToken = null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refresh_withoutRefreshToken_clearsAndDoesNotCallTheServer() {
        storage.seed(accessToken = "expired", refreshToken = null, user = null)

        val result = refresher.refresh(staleAccessToken = "expired")

        assertEquals(RefreshResult.NoRefreshToken, result)
        assertNull(storage.getAccessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refresh_definitiveRejections_clearTheStoredSession() {
        for (status in listOf(400, 401, 403, 409)) {
            storage.seed(accessToken = "expired", refreshToken = "refresh-old", user = null)
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":{"code":"x"}}"""))

            val result = refresher.refresh(staleAccessToken = "expired")

            assertEquals("HTTP $status", status, (result as RefreshResult.Rejected).statusCode)
            assertNull("HTTP $status must clear the refresh token", storage.getRefreshToken())
            assertNull(storage.getAccessToken())
        }
    }

    @Test
    fun refresh_transientFailures_keepTheStoredSession() {
        val outages = listOf(
            MockResponse().setResponseCode(500),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(429),
            MockResponse().setResponseCode(418),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            MockResponse().setResponseCode(200).setBody("not json")
        )
        for (outage in outages) {
            storage.seed(accessToken = "expired", refreshToken = "refresh-old", user = null)
            server.enqueue(outage)

            val result = refresher.refresh(staleAccessToken = "expired")

            assertTrue("unexpected $result", result is RefreshResult.Transient ||
                result is RefreshResult.NetworkError || result is RefreshResult.Failed)
            assertEquals("refresh-old", storage.getRefreshToken())
            assertEquals("expired", storage.getAccessToken())
            assertEquals(0, storage.clearAuthDataCalls.get())
        }
    }

    @Test
    fun concurrentCallers_shareOneRequest_andReadTheRefreshTokenOnlyOnceTheyHoldTheLock() {
        val backend = RotatingRefreshBackend()
        server.dispatcher = backend
        backend.holdRefreshes()
        storage.seed(accessToken = null, refreshToken = "refresh-0", user = null)

        val callers = 6
        val pool = Executors.newFixedThreadPool(callers)
        try {
            val started = CountDownLatch(callers)
            val results = (1..callers).map {
                pool.submit<RefreshResult> {
                    started.countDown()
                    refresher.refresh(staleAccessToken = null)
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertTrue(backend.awaitRefreshArrived())
            Thread.sleep(200) // the other callers are blocked on the refresher's lock
            backend.releaseRefreshes()

            val outcomes = results.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, backend.refreshCalls.get())
            assertEquals(0, backend.reuseRejections.get())
            assertEquals(1, outcomes.count { it is RefreshResult.Refreshed })
            assertEquals(callers - 1, outcomes.count { it == RefreshResult.AlreadyRefreshed("access-1") })
            assertEquals("refresh-1", storage.getRefreshToken())
        } finally {
            pool.shutdownNow()
        }
    }
}
