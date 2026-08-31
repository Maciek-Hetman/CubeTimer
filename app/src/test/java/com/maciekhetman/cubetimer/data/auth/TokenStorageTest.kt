package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TokenStorageTest {

    private lateinit var context: Context
    private lateinit var tokenStorage: TokenStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use unique file name per test to prevent state pollution
        val prefName = "test_prefs_${UUID.randomUUID()}"
        tokenStorage = EncryptedTokenStorage(context, prefName)
    }

    @Test
    fun `in-memory access token flow reflects setAccessToken`() = runTest {
        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.accessTokenFlow.first())

        tokenStorage.setAccessToken("jwt-access-token-123")
        assertEquals("jwt-access-token-123", tokenStorage.getAccessToken())
        assertEquals("jwt-access-token-123", tokenStorage.accessTokenFlow.first())

        tokenStorage.setAccessToken(null)
        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.accessTokenFlow.first())
    }

    @Test
    fun `saveAuthSession persists refresh token and metadata and sets in-memory access token`() {
        tokenStorage.saveAuthSession(
            accessToken = "access-100",
            refreshToken = "refresh-100",
            userId = "user-100",
            userEmail = "cuber@example.com",
            userRole = "admin",
            emailVerified = true,
            displayName = "Tymon Kolasinski"
        )

        assertEquals("access-100", tokenStorage.getAccessToken())
        assertEquals("refresh-100", tokenStorage.getRefreshToken())
        assertEquals("user-100", tokenStorage.getUserId())
        assertEquals("cuber@example.com", tokenStorage.getUserEmail())
        assertEquals("admin", tokenStorage.getUserRole())
        assertTrue(tokenStorage.isUserEmailVerified())
        assertEquals("Tymon Kolasinski", tokenStorage.getDisplayName())
        assertTrue(tokenStorage.isAuthenticated())

        val cachedUser = tokenStorage.getCachedUser()
        assertNotNull(cachedUser)
        assertEquals("user-100", cachedUser?.id)
        assertEquals("cuber@example.com", cachedUser?.email)
        assertEquals("Tymon Kolasinski", cachedUser?.displayName)
        assertEquals(UserRole.ADMIN, cachedUser?.userRole)
        assertTrue(cachedUser?.emailVerified == true)
    }

    @Test
    fun `saveUser updates persistent user details`() {
        val user = User(
            id = "user-200",
            email = "yiheng@example.com",
            displayName = "Yiheng Wang",
            emailVerified = true,
            userRole = UserRole.USER
        )

        tokenStorage.saveUser(user)

        val cached = tokenStorage.getCachedUser()
        assertNotNull(cached)
        assertEquals("user-200", cached?.id)
        assertEquals("yiheng@example.com", cached?.email)
        assertEquals("Yiheng Wang", cached?.displayName)
        assertEquals(UserRole.USER, cached?.userRole)
    }

    @Test
    fun `getDeviceId generates UUID on first run and remains stable`() {
        val deviceId1 = tokenStorage.getDeviceId()
        assertNotNull(deviceId1)
        assertTrue(deviceId1.isNotBlank())

        val deviceId2 = tokenStorage.getDeviceId()
        assertEquals(deviceId1, deviceId2)
    }

    @Test
    fun `clearAuthData wipes tokens and user metadata but retains deviceId`() {
        val deviceIdBefore = tokenStorage.getDeviceId()

        tokenStorage.saveAuthSession(
            accessToken = "access-300",
            refreshToken = "refresh-300",
            userId = "user-300",
            userEmail = "lukas@example.com",
            userRole = "user",
            emailVerified = true
        )

        tokenStorage.clearAuthData()

        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.getRefreshToken())
        assertNull(tokenStorage.getUserId())
        assertNull(tokenStorage.getUserEmail())
        assertNull(tokenStorage.getUserRole())
        assertNull(tokenStorage.getCachedUser())
        assertFalse(tokenStorage.isAuthenticated())

        // Critical: deviceId must remain unchanged after logout/clearAuthData
        val deviceIdAfter = tokenStorage.getDeviceId()
        assertEquals(deviceIdBefore, deviceIdAfter)
    }

    @Test
    fun `clearAll wipes all storage including deviceId`() {
        val deviceIdBefore = tokenStorage.getDeviceId()

        tokenStorage.saveAuthSession(
            accessToken = "access-400",
            refreshToken = "refresh-400",
            userId = "user-400",
            userEmail = "sean@example.com",
            userRole = "user",
            emailVerified = true
        )

        tokenStorage.clearAll()

        assertNull(tokenStorage.getAccessToken())
        assertNull(tokenStorage.getRefreshToken())
        assertNull(tokenStorage.getUserId())
        assertNull(tokenStorage.getUserEmail())
        assertNull(tokenStorage.getCachedUser())
    }
}
