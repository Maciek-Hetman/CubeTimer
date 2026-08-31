package com.maciekhetman.cubetimer.data.auth

import com.maciekhetman.cubetimer.model.AuthState
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import com.maciekhetman.cubetimer.model.currentUser
import com.maciekhetman.cubetimer.model.isAdmin
import com.maciekhetman.cubetimer.model.isAuthenticated
import com.maciekhetman.cubetimer.model.isGuest
import com.maciekhetman.cubetimer.model.isLoading
import com.maciekhetman.cubetimer.model.ownerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {

    private val regularUser = User(
        id = "user-123",
        email = "user@test.com",
        displayName = "Speedcuber",
        emailVerified = true,
        userRole = UserRole.USER
    )

    private val adminUser = User(
        id = "admin-456",
        email = "admin@test.com",
        displayName = "Admin Cuber",
        emailVerified = true,
        userRole = UserRole.ADMIN
    )

    @Test
    fun `loading state has correct properties`() {
        val state: AuthState = AuthState.Loading

        assertNull(state.currentUser)
        assertFalse(state.isAuthenticated)
        assertFalse(state.isAdmin)
        assertFalse(state.isGuest)
        assertTrue(state.isLoading)
        assertEquals("guest", state.ownerId)
    }

    @Test
    fun `guest state has correct properties`() {
        val state: AuthState = AuthState.Guest

        assertNull(state.currentUser)
        assertFalse(state.isAuthenticated)
        assertFalse(state.isAdmin)
        assertTrue(state.isGuest)
        assertFalse(state.isLoading)
        assertEquals("guest", state.ownerId)
    }

    @Test
    fun `authenticated user state has correct properties`() {
        val state: AuthState = AuthState.Authenticated(regularUser)

        assertEquals(regularUser, state.currentUser)
        assertTrue(state.isAuthenticated)
        assertFalse(state.isAdmin)
        assertFalse(state.isGuest)
        assertFalse(state.isLoading)
        assertEquals("user-123", state.ownerId)
    }

    @Test
    fun `admin user state has correct properties`() {
        val state: AuthState = AuthState.Admin(adminUser)

        assertEquals(adminUser, state.currentUser)
        assertTrue(state.isAuthenticated)
        assertTrue(state.isAdmin)
        assertFalse(state.isGuest)
        assertFalse(state.isLoading)
        assertEquals("admin-456", state.ownerId)
    }
}
