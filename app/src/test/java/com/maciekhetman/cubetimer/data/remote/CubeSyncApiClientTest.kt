package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.ChangePasswordRequest
import com.maciekhetman.cubetimer.data.remote.dto.GoogleAuthRequest
import com.maciekhetman.cubetimer.data.remote.dto.LoginRequest
import com.maciekhetman.cubetimer.data.remote.dto.RegisterRequest
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CubeSyncApiClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiClient: CubeSyncApiClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString()
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
        val apiService = NetworkModule.provideAuthApiService(baseUrl, okHttpClient)
        apiClient = NetworkModule.provideCubeSyncApiClient(apiService)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `register success returns StatusResponse`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody("""{"status":"verification_required"}""")
        )

        val response = apiClient.register(RegisterRequest("test@example.com", "Password123!"))
        assertEquals("verification_required", response.status)

        val req = mockWebServer.takeRequest()
        assertEquals("/v1/auth/register", req.path)
    }

    @Test
    fun `register with duplicate email throws EmailAlreadyExists`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"email_in_use","message":"An account with this email already exists"}}""")
        )

        try {
            apiClient.register(RegisterRequest("existing@example.com", "Password123!"))
            fail("Expected EmailAlreadyExists")
        } catch (e: AuthException.EmailAlreadyExists) {
            assertTrue(e.message.contains("email already exists"))
        }
    }

    @Test
    fun `login success returns AuthResponse with user and tokens`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "access_token": "acc-token-123",
                        "refresh_token": "ref-token-123",
                        "token_type": "Bearer",
                        "expires_in": 900,
                        "user": {
                            "id": "u-123",
                            "email": "user@example.com",
                            "display_name": "Speedcuber",
                            "user_role": "user",
                            "email_verified": true,
                            "created_at": "2026-08-30T10:00:00Z"
                        }
                    }
                """.trimIndent())
        )

        val auth = apiClient.login(LoginRequest("user@example.com", "Secret12345!"))
        assertEquals("acc-token-123", auth.accessToken)
        assertEquals("ref-token-123", auth.refreshToken)
        assertEquals("u-123", auth.user.id)
        assertEquals("user@example.com", auth.user.email)
        assertEquals("Speedcuber", auth.user.displayName)
        assertTrue(auth.user.emailVerified)
    }

    @Test
    fun `login with invalid credentials throws InvalidCredentials`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":"invalid_credentials","message":"email or password is incorrect"}}""")
        )

        try {
            apiClient.login(LoginRequest("user@example.com", "WrongPassword!"))
            fail("Expected InvalidCredentials")
        } catch (e: AuthException.InvalidCredentials) {
            assertTrue(e.message.contains("incorrect"))
        }
    }

    @Test
    fun `login with unverified email throws EmailNotVerified`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"email_not_verified","message":"Please verify your email address"}}""")
        )

        try {
            apiClient.login(LoginRequest("unverified@example.com", "Password123!"))
            fail("Expected EmailNotVerified")
        } catch (e: AuthException.EmailNotVerified) {
            assertTrue(e.message.contains("verify"))
        }
    }

    @Test
    fun `refreshToken success returns rotated tokens`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "access_token": "acc-token-fresh",
                        "refresh_token": "ref-token-rotated",
                        "token_type": "Bearer",
                        "expires_in": 900,
                        "user": {
                            "id": "u-123",
                            "email": "user@example.com",
                            "user_role": "user",
                            "email_verified": true
                        }
                    }
                """.trimIndent())
        )

        val auth = apiClient.refreshToken("old-ref-token")
        assertEquals("acc-token-fresh", auth.accessToken)
        assertEquals("ref-token-rotated", auth.refreshToken)
    }

    @Test
    fun `refreshToken with reused token throws RefreshTokenReused`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setBody("""{"error":{"code":"refresh_token_reused","message":"Refresh token reuse detected"}}""")
        )

        try {
            apiClient.refreshToken("replayed-token")
            fail("Expected RefreshTokenReused")
        } catch (e: AuthException.RefreshTokenReused) {
            assertTrue(e.message.contains("reuse detected"))
        }
    }

    @Test
    fun `logout success executes without error`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        apiClient.logout("ref-token-to-revoke")
        val req = mockWebServer.takeRequest()
        assertEquals("/v1/auth/logout", req.path)
    }

    @Test
    fun `verifyEmail success returns AuthResponse`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "access_token": "acc-verified",
                        "refresh_token": "ref-verified",
                        "user": {
                            "id": "u-456",
                            "email": "verified@example.com",
                            "user_role": "user",
                            "email_verified": true
                        }
                    }
                """.trimIndent())
        )

        val auth = apiClient.verifyEmail("valid-email-token")
        assertEquals("acc-verified", auth.accessToken)
        assertTrue(auth.user.emailVerified)
    }

    @Test
    fun `verifyEmail with invalid token throws InvalidToken`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"code":"invalid_token","message":"The verification token is invalid"}}""")
        )

        try {
            apiClient.verifyEmail("expired-token")
            fail("Expected InvalidToken")
        } catch (e: AuthException.InvalidToken) {
            assertTrue(e.message.contains("invalid"))
        }
    }

    @Test
    fun `resendVerificationEmail returns StatusResponse`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody("""{"status":"accepted"}""")
        )

        val resp = apiClient.resendVerificationEmail("test@example.com")
        assertEquals("accepted", resp.status)
    }

    @Test
    fun `requestPasswordReset returns StatusResponse`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody("""{"status":"accepted"}""")
        )

        val resp = apiClient.requestPasswordReset("forgot@example.com")
        assertEquals("accepted", resp.status)
    }

    @Test
    fun `confirmPasswordReset returns AuthResponse`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "access_token": "acc-reset",
                        "refresh_token": "ref-reset",
                        "user": {
                            "id": "u-789",
                            "email": "reset@example.com",
                            "user_role": "user",
                            "email_verified": true
                        }
                    }
                """.trimIndent())
        )

        val auth = apiClient.confirmPasswordReset("reset-token-123", "NewSecret12345!")
        assertEquals("acc-reset", auth.accessToken)
    }

    @Test
    fun `loginWithGoogle returns AuthResponse`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "access_token": "acc-google",
                        "refresh_token": "ref-google",
                        "user": {
                            "id": "u-google",
                            "email": "googleuser@gmail.com",
                            "user_role": "user",
                            "email_verified": true
                        }
                    }
                """.trimIndent())
        )

        val auth = apiClient.loginWithGoogle(GoogleAuthRequest(idToken = "google-id-token-xyz"))
        assertEquals("acc-google", auth.accessToken)
        assertEquals("googleuser@gmail.com", auth.user.email)
    }

    @Test
    fun `linkGoogle attaches authorization header and succeeds`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        apiClient.linkGoogle("id-token-link", authToken = "Bearer jwt-session-token")
        val req = mockWebServer.takeRequest()
        assertEquals("/v1/auth/link/google", req.path)
        assertEquals("Bearer jwt-session-token", req.getHeader("Authorization"))
    }

    @Test
    fun `getCurrentUser returns UserDto`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""
                    {
                        "id": "u-me",
                        "email": "me@example.com",
                        "display_name": "My Name",
                        "user_role": "admin",
                        "email_verified": true
                    }
                """.trimIndent())
        )

        val user = apiClient.getCurrentUser("jwt-token")
        assertEquals("u-me", user.id)
        assertEquals("me@example.com", user.email)
        assertEquals("admin", user.userRole)
    }

    @Test
    fun `changePassword executes successfully`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        apiClient.changePassword(
            ChangePasswordRequest(currentPassword = "OldPassword1!", newPassword = "NewPassword1!"),
            authToken = "jwt-token"
        )
        val req = mockWebServer.takeRequest()
        assertEquals("/v1/me/password", req.path)
    }

    @Test
    fun `deleteAccount executes successfully`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        apiClient.deleteAccount("jwt-token")
        val req = mockWebServer.takeRequest()
        assertEquals("/v1/me", req.path)
        assertEquals("DELETE", req.method)
    }

    @Test
    fun `rate limited request throws RateLimited`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":"rate_limited","message":"Too many requests"}}""")
        )

        try {
            apiClient.login(LoginRequest("spammer@example.com", "Password123!"))
            fail("Expected RateLimited")
        } catch (e: AuthException.RateLimited) {
            assertTrue(e.message.contains("Too many requests"))
        }
    }

    @Test
    fun `network disconnect throws NetworkError`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )

        try {
            apiClient.login(LoginRequest("user@example.com", "Password123!"))
            fail("Expected NetworkError")
        } catch (e: AuthException.NetworkError) {
            assertNotNull(e.cause)
        }
    }
}
