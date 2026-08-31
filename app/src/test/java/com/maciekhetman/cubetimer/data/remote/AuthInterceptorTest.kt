package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.auth.TokenStorage
import com.maciekhetman.cubetimer.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var fakeTokenStorage: FakeTokenStorage
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        fakeTokenStorage = FakeTokenStorage()
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeTokenStorage))
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `attaches Bearer token, device ID, and sync protocol when access token is present`() {
        fakeTokenStorage.storedAccessToken = "test-jwt-access-token"
        fakeTokenStorage.storedDeviceId = "device-uuid-12345"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/me"))
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.close()

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer test-jwt-access-token", recordedRequest.getHeader(AuthInterceptor.HEADER_AUTHORIZATION))
        assertEquals("device-uuid-12345", recordedRequest.getHeader(AuthInterceptor.HEADER_DEVICE_ID))
        assertEquals("2", recordedRequest.getHeader(AuthInterceptor.HEADER_SYNC_PROTOCOL))
    }

    @Test
    fun `omits Authorization header when access token is null`() {
        fakeTokenStorage.storedAccessToken = null
        fakeTokenStorage.storedDeviceId = "device-uuid-67890"

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/auth/login"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.close()

        val recordedRequest = mockWebServer.takeRequest()
        assertNull(recordedRequest.getHeader(AuthInterceptor.HEADER_AUTHORIZATION))
        assertEquals("device-uuid-67890", recordedRequest.getHeader(AuthInterceptor.HEADER_DEVICE_ID))
        assertEquals("2", recordedRequest.getHeader(AuthInterceptor.HEADER_SYNC_PROTOCOL))
    }

    @Test
    fun `attaches Content-Type application json on mutating request with body`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val request = Request.Builder()
            .url(mockWebServer.url("/v1/sync"))
            .post("{}".toRequestBody())
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.close()

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("application/json", recordedRequest.getHeader(AuthInterceptor.HEADER_CONTENT_TYPE))
    }

    private class FakeTokenStorage : TokenStorage {
        var storedAccessToken: String? = null
        var storedRefreshToken: String? = null
        var storedDeviceId: String = "default-device-id"

        private val _flow = MutableStateFlow<String?>(storedAccessToken)
        override val accessTokenFlow: StateFlow<String?> = _flow

        override fun getAccessToken(): String? = storedAccessToken
        override fun setAccessToken(token: String?) {
            storedAccessToken = token
            _flow.value = token
        }
        override fun getRefreshToken(): String? = storedRefreshToken
        override fun setRefreshToken(token: String?) { storedRefreshToken = token }
        override fun getUserId(): String? = null
        override fun getUserEmail(): String? = null
        override fun getUserRole(): String? = null
        override fun isUserEmailVerified(): Boolean = false
        override fun getDisplayName(): String? = null
        override fun getCachedUser(): User? = null
        override fun saveAuthSession(
            accessToken: String,
            refreshToken: String,
            userId: String,
            userEmail: String,
            userRole: String,
            emailVerified: Boolean,
            displayName: String?
        ) {
            setAccessToken(accessToken)
            this.storedRefreshToken = refreshToken
        }
        override fun saveUser(user: User) {}
        override fun getDeviceId(): String = storedDeviceId
        override fun clearAuthData() {
            setAccessToken(null)
            storedRefreshToken = null
        }
        override fun clearAll() {
            setAccessToken(null)
            storedRefreshToken = null
        }
    }
}
