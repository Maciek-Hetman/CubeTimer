package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.data.remote.dto.ApiErrorResponse
import com.maciekhetman.cubetimer.model.AuthException
import kotlinx.serialization.json.Json

object ErrorParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseApiError(errorBodyString: String?): ApiErrorResponse? {
        if (errorBodyString.isNullOrBlank()) return null
        return try {
            json.decodeFromString<ApiErrorResponse>(errorBodyString)
        } catch (_: Exception) {
            null
        }
    }

    fun toAuthException(
        errorCode: String?,
        message: String,
        httpStatusCode: Int
    ): AuthException {
        return when (errorCode?.lowercase()?.trim()) {
            "invalid_credentials" -> AuthException.InvalidCredentials(message)
            "email_not_verified" -> AuthException.EmailNotVerified(message)
            "email_in_use", "email_already_exists" -> AuthException.EmailAlreadyExists(message)
            "invalid_token" -> AuthException.InvalidToken(message)
            "invalid_refresh_token" -> AuthException.InvalidRefreshToken(message)
            "refresh_token_reused" -> AuthException.RefreshTokenReused(message)
            "account_link_required" -> AuthException.AccountLinkRequired(message)
            "invalid_social_token", "invalid_social_code" -> AuthException.InvalidSocialToken(message)
            "identity_in_use", "provider_already_linked" -> AuthException.IdentityAlreadyLinked(message)
            "rate_limited" -> AuthException.RateLimited(message)
            "invalid_password" -> AuthException.InvalidPassword(message)
            "invalid_email" -> AuthException.InvalidEmail(message)
            "email_delivery_failed" -> AuthException.EmailDeliveryFailed(message)
            "unauthorized" -> AuthException.Unauthorized(message)
            "forbidden" -> AuthException.Forbidden(message)
            "cursor_expired" -> AuthException.CursorExpired(message)
            else -> when (httpStatusCode) {
                401 -> AuthException.Unauthorized(message)
                403 -> AuthException.Forbidden(message)
                429 -> AuthException.RateLimited(message)
                else -> AuthException.ApiError(
                    errorCode = errorCode ?: "unknown_error",
                    message = message,
                    httpStatusCode = httpStatusCode
                )
            }
        }
    }
}
