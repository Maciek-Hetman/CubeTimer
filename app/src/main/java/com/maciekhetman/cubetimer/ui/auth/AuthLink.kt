package com.maciekhetman.cubetimer.ui.auth

import android.net.Uri

/**
 * A one-time link from a CubeSync email, opened in the app instead of the web client. The server
 * builds them as `CLIENT_URL/verify-email?token=…` and `CLIENT_URL/reset-password?token=…`.
 */
sealed interface AuthLink {
    val token: String

    data class VerifyEmail(override val token: String) : AuthLink
    data class ResetPassword(override val token: String) : AuthLink

    companion object {
        /** The web client's host. Keep in step with the intent filter in `AndroidManifest.xml`. */
        const val HOST = "cubetimer.cc"

        private const val VERIFY_EMAIL_PATH = "/verify-email"
        private const val RESET_PASSWORD_PATH = "/reset-password"

        /** Returns null for anything that is not one of the two links, or that carries no token. */
        fun fromUri(uri: Uri): AuthLink? {
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.host.equals(HOST, ignoreCase = true)) return null
            val token = uri.getQueryParameter("token")?.trim().orEmpty()
            if (token.isEmpty()) return null
            return when (uri.path?.trimEnd('/')) {
                VERIFY_EMAIL_PATH -> VerifyEmail(token)
                RESET_PASSWORD_PATH -> ResetPassword(token)
                else -> null
            }
        }
    }
}
