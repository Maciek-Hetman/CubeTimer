package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.data.remote.mapper.toDomain
import com.maciekhetman.cubetimer.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMappersTest {

    @Test
    fun `map UserDto to domain User`() {
        val dto = UserDto(
            id = "uid-100",
            email = "cuber@example.com",
            displayName = "Max Park",
            userRole = "admin",
            emailVerified = true,
            createdAt = "2026-08-30T10:00:00Z"
        )

        val domain = dto.toDomain()

        assertEquals("uid-100", domain.id)
        assertEquals("cuber@example.com", domain.email)
        assertEquals("Max Park", domain.displayName)
        assertEquals(UserRole.ADMIN, domain.userRole)
        assertTrue(domain.emailVerified)
        assertEquals("2026-08-30T10:00:00Z", domain.createdAt)
    }
}
