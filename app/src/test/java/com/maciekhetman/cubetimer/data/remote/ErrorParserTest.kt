package com.maciekhetman.cubetimer.data.remote

import com.maciekhetman.cubetimer.model.AuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorParserTest {

    @Test
    fun `parse valid ApiErrorResponse JSON`() {
        val json = """{"error":{"code":"invalid_credentials","message":"email or password is incorrect"}}"""
        val parsed = ErrorParser.parseApiError(json)

        assertEquals("invalid_credentials", parsed?.error?.code)
        assertEquals("email or password is incorrect", parsed?.error?.message)
    }

    @Test
    fun `parse empty or null error body returns null`() {
        assertNull(ErrorParser.parseApiError(null))
        assertNull(ErrorParser.parseApiError(""))
        assertNull(ErrorParser.parseApiError("   "))
    }

    @Test
    fun `parse invalid JSON returns null without throwing`() {
        assertNull(ErrorParser.parseApiError("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `map error codes to typed AuthExceptions`() {
        assertTrue(ErrorParser.toAuthException("invalid_credentials", "Bad creds", 401) is AuthException.InvalidCredentials)
        assertTrue(ErrorParser.toAuthException("email_not_verified", "Verify first", 403) is AuthException.EmailNotVerified)
        assertTrue(ErrorParser.toAuthException("email_in_use", "Taken", 409) is AuthException.EmailAlreadyExists)
        assertTrue(ErrorParser.toAuthException("email_already_exists", "Taken", 409) is AuthException.EmailAlreadyExists)
        assertTrue(ErrorParser.toAuthException("invalid_token", "Expired", 400) is AuthException.InvalidToken)
        assertTrue(ErrorParser.toAuthException("invalid_refresh_token", "Bad refresh", 401) is AuthException.InvalidRefreshToken)
        assertTrue(ErrorParser.toAuthException("refresh_token_reused", "Reused", 409) is AuthException.RefreshTokenReused)
        assertTrue(ErrorParser.toAuthException("account_link_required", "Link needed", 409) is AuthException.AccountLinkRequired)
        assertTrue(ErrorParser.toAuthException("invalid_social_token", "Social fail", 401) is AuthException.InvalidSocialToken)
        assertTrue(ErrorParser.toAuthException("identity_in_use", "Linked", 409) is AuthException.IdentityAlreadyLinked)
        assertTrue(ErrorParser.toAuthException("rate_limited", "Slow down", 429) is AuthException.RateLimited)
        assertTrue(ErrorParser.toAuthException("invalid_password", "Weak", 400) is AuthException.InvalidPassword)
        assertTrue(ErrorParser.toAuthException("invalid_email", "Bad format", 400) is AuthException.InvalidEmail)
        assertTrue(ErrorParser.toAuthException("email_delivery_failed", "SMTP down", 503) is AuthException.EmailDeliveryFailed)
        assertTrue(ErrorParser.toAuthException("unauthorized", "Unauthorized", 401) is AuthException.Unauthorized)
        assertTrue(ErrorParser.toAuthException("forbidden", "Forbidden", 403) is AuthException.Forbidden)
    }

    @Test
    fun `fallback to HTTP status code when code is unrecognized`() {
        val e401 = ErrorParser.toAuthException("some_custom_code", "Unauth", 401)
        assertTrue(e401 is AuthException.Unauthorized)

        val e403 = ErrorParser.toAuthException("some_custom_code", "Forbidden", 403)
        assertTrue(e403 is AuthException.Forbidden)

        val e429 = ErrorParser.toAuthException("some_custom_code", "Rate limit", 429)
        assertTrue(e429 is AuthException.RateLimited)

        val e500 = ErrorParser.toAuthException("internal_error", "Server crashed", 500)
        assertTrue(e500 is AuthException.ApiError)
        assertEquals("internal_error", (e500 as AuthException.ApiError).errorCode)
        assertEquals(500, e500.httpStatusCode)
    }
}
