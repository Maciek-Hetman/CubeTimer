package com.maciekhetman.cubetimer.model

/**
 * Domain representation of an authenticated CubeTimer / CubeSync user.
 */
data class User(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val emailVerified: Boolean = false,
    val userRole: UserRole = UserRole.USER,
    val createdAt: String? = null
) {
    val isAdmin: Boolean
        get() = userRole == UserRole.ADMIN

    val isEmailVerified: Boolean
        get() = emailVerified
}

enum class UserRole {
    USER,
    ADMIN;

    companion object {
        fun fromString(role: String?): UserRole = when (role?.lowercase()?.trim()) {
            "admin" -> ADMIN
            else -> USER
        }
    }
}
