package com.maciekhetman.cubetimer.model

/**
 * Represents the current authentication and authorization state of the app.
 */
sealed class AuthState {
    /**
     * Initial transient state during startup while TokenStorage is inspected
     * and session hydration/token refresh is in progress.
     */
    data object Loading : AuthState()

    /**
     * Unauthenticated guest state.
     * Solves and sessions are stored with owner_id = "guest".
     */
    data object Guest : AuthState()

    /**
     * Authenticated regular user.
     * Solves and sessions are stored with owner_id = user.id.
     */
    data class Authenticated(val user: User) : AuthState()

    /**
     * Authenticated administrator (user.userRole == UserRole.ADMIN).
     * Grants access to Admin Metrics dashboards (/v1/admin/stats).
     */
    data class Admin(val user: User) : AuthState()
}

/**
 * Returns the currently authenticated user if in Authenticated or Admin state; null otherwise.
 */
val AuthState.currentUser: User?
    get() = when (this) {
        is AuthState.Authenticated -> user
        is AuthState.Admin -> user
        AuthState.Guest, AuthState.Loading -> null
    }

/**
 * True if the current state is Authenticated or Admin.
 */
val AuthState.isAuthenticated: Boolean
    get() = this is AuthState.Authenticated || this is AuthState.Admin

/**
 * True if the current state is Admin.
 */
val AuthState.isAdmin: Boolean
    get() = this is AuthState.Admin

/**
 * True if the current state is Guest.
 */
val AuthState.isGuest: Boolean
    get() = this is AuthState.Guest

/**
 * True if the current state is Loading.
 */
val AuthState.isLoading: Boolean
    get() = this is AuthState.Loading

/**
 * Active owner ID string: "guest" when unauthenticated, or the user's UUID when authenticated.
 */
val AuthState.ownerId: String
    get() = currentUser?.id ?: "guest"
