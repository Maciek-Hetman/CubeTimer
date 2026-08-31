package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.UserDto
import com.maciekhetman.cubetimer.data.remote.mapper.toDomain
import com.maciekhetman.cubetimer.data.remote.mapper.toDto
import com.maciekhetman.cubetimer.model.User
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
        assertTrue(domain.isAdmin)
        assertEquals("2026-08-30T10:00:00Z", domain.createdAt)
    }

    @Test
    fun `map domain User to UserDto`() {
        val domain = User(
            id = "uid-200",
            email = "feliks@example.com",
            displayName = "Feliks Zemdegs",
            userRole = UserRole.USER,
            emailVerified = true,
            createdAt = "2026-08-30T11:00:00Z"
        )

        val dto = domain.toDto()

        assertEquals("uid-200", dto.id)
        assertEquals("feliks@example.com", dto.email)
        assertEquals("Feliks Zemdegs", dto.displayName)
        assertEquals("user", dto.userRole)
        assertTrue(dto.emailVerified)
        assertEquals("2026-08-30T11:00:00Z", dto.createdAt)
    }
}
