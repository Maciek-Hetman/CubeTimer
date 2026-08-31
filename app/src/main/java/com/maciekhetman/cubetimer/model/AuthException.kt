package com.maciekhetman.cubetimer.model

/**
 * Sealed exception hierarchy representing domain-level authentication, authorization, and network errors.
 */
sealed class AuthException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /** 401 invalid_credentials: email or password incorrect */
    class InvalidCredentials(message: String = "Email or password is incorrect") :
        AuthException(message)

    /** 403 email_not_verified: user must verify email before logging in */
    class EmailNotVerified(message: String = "Email address has not been verified") :
        AuthException(message)

    /** 409 email_in_use / email_already_exists: registration email taken */
    class EmailAlreadyExists(message: String = "An account with this email already exists") :
        AuthException(message)

    /** 400 invalid_token: token for email verification or password reset invalid/expired */
    class InvalidToken(message: String = "The verification or reset token is invalid or expired") :
        AuthException(message)

    /** 401 invalid_refresh_token: refresh token revoked or expired */
    class InvalidRefreshToken(message: String = "The session refresh token is invalid or expired") :
        AuthException(message)

    /** 409 refresh_token_reused: token reuse detected; server revoked token family */
    class RefreshTokenReused(message: String = "Refresh token reuse detected; session revoked") :
        AuthException(message)

    /** 409 account_link_required: federated email belongs to existing password account */
    class AccountLinkRequired(message: String = "Account exists with password; please login and link Google account") :
        AuthException(message)

    /** 401 invalid_social_token / invalid_social_code */
    class InvalidSocialToken(message: String = "Google authentication failed: invalid token or authorization code") :
        AuthException(message)

    /** 409 identity_in_use / provider_already_linked */
    class IdentityAlreadyLinked(message: String = "Google account is already linked to another user") :
        AuthException(message)

    /** 429 rate_limited: too many requests */
    class RateLimited(message: String = "Too many requests; please try again later") :
        AuthException(message)

    /** 400 invalid_password: password does not satisfy complexity (10..128 chars) */
    class InvalidPassword(message: String = "Password does not meet requirements (10-128 characters)") :
        AuthException(message)

    /** 400 invalid_email */
    class InvalidEmail(message: String = "Invalid email format") :
        AuthException(message)

    /** 503 email_delivery_failed */
    class EmailDeliveryFailed(message: String = "Failed to send email; service temporarily unavailable") :
        AuthException(message)

    /** 401 unauthorized: access token missing, invalid, or expired */
    class Unauthorized(message: String = "Authentication credentials missing or invalid") :
        AuthException(message)

    /** 403 forbidden: user lacks required permissions (e.g. non-admin accessing admin API) */
    class Forbidden(message: String = "You do not have permission to perform this action") :
        AuthException(message)

    /** 409 cursor_expired: sync cursor has expired; snapshot resync required */
    class CursorExpired(message: String = "Sync cursor has expired; snapshot resync required") :
        AuthException(message)

    /** General API error returned by backend */
    class ApiError(
        val errorCode: String,
        override val message: String,
        val httpStatusCode: Int
    ) : AuthException("API error ($httpStatusCode) [$errorCode]: $message")

    /** Network connectivity error (offline, timeout, DNS failure) */
    class NetworkError(
        message: String = "Network connection failed",
        cause: Throwable? = null
    ) : AuthException(message, cause)

    /** Kotlinx Serialization / Deserialization error */
    class SerializationError(
        message: String = "Failed to parse server response",
        cause: Throwable? = null
    ) : AuthException(message, cause)

    /** 500 server_error: internal server failure */
    class ServerError(
        message: String = "An internal server error occurred",
        cause: Throwable? = null
    ) : AuthException(message, cause)

    /** Unknown / generic error */
    class Unknown(
        message: String = "An unexpected error occurred",
        cause: Throwable? = null
    ) : AuthException(message, cause)
}
