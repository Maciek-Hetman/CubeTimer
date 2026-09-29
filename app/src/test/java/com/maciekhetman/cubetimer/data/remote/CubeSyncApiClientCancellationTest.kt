package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.DeviceDto
import com.maciekhetman.cubetimer.data.remote.dto.SyncRequest
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Cancelling a coroutine that is waiting on an API call is not an API failure: the client must let
 * the [CancellationException] through untouched instead of wrapping it into an [AuthException],
 * which callers (like the sync engine's dedicated cancellation branch) would then mistake for a
 * genuine error.
 */
class CubeSyncApiClientCancellationTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: CubeSyncApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        apiClient = NetworkModule.provideCubeSyncApiClient(
            NetworkModule.provideAuthApiService(server.url("/").toString(), okHttpClient)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Runs [call] in a coroutine, waits until the server has received the request (which it then
     * never answers), cancels the coroutine and returns what [call] itself observed.
     */
    private fun cancelWhileWaiting(call: suspend () -> Unit): Throwable = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val observed = CompletableDeferred<Throwable?>()

        val job = launch(Dispatchers.IO) {
            try {
                call()
                observed.complete(null)
            } catch (t: Throwable) {
                observed.complete(t)
                throw t
            }
        }
        assertNotNull("request never reached the server", server.takeRequest(10, TimeUnit.SECONDS))
        job.cancel()

        val thrown = withTimeout(10_000) { observed.await() }
        assertNotNull("the call returned instead of being cancelled", thrown)
        thrown!!
    }

    private fun assertPlainCancellation(thrown: Throwable) {
        assertTrue("expected CancellationException but was $thrown", thrown is CancellationException)
        assertFalse("cancellation must not be wrapped as AuthException: $thrown", thrown is AuthException)
    }

    @Test
    fun cancelledCall_returningABody_rethrowsCancellationException() {
        assertPlainCancellation(cancelWhileWaiting { apiClient.refreshToken("refresh-token") })
    }

    @Test
    fun cancelledCall_returningNoBody_rethrowsCancellationException() {
        assertPlainCancellation(cancelWhileWaiting { apiClient.logout("refresh-token") })
    }

    @Test
    fun cancelledSync_rethrowsCancellationException() {
        assertPlainCancellation(cancelWhileWaiting { apiClient.sync(SyncRequest(device = DeviceDto(id = "device", name = "test", platform = "android")), authToken = "access") })
    }

    @Test
    fun connectionFailure_isStillReportedAsNetworkError() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val thrown = runCatching { apiClient.refreshToken("refresh-token") }.exceptionOrNull()

        assertTrue("expected NetworkError but was $thrown", thrown is AuthException.NetworkError)
        assertEquals(1, server.requestCount)
    }
}
