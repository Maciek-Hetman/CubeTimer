package com.maciekhetman.cubetimer.ui.auth

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Only the two one-time links the server emails are recognised, and only with a token. */
@RunWith(RobolectricTestRunner::class)
class AuthLinkTest {

    private fun parse(url: String): AuthLink? = AuthLink.fromUri(Uri.parse(url))

    @Test
    fun verifyEmailLinkCarriesItsToken() {
        assertEquals(
            AuthLink.VerifyEmail("abc123"),
            parse("https://cubetimer.cc/verify-email?token=abc123")
        )
    }

    @Test
    fun resetPasswordLinkCarriesItsToken() {
        assertEquals(
            AuthLink.ResetPassword("abc123"),
            parse("https://cubetimer.cc/reset-password?token=abc123")
        )
    }

    @Test
    fun tokenIsUrlDecodedAndTrimmed() {
        // The server escapes the token with url.QueryEscape.
        assertEquals(
            AuthLink.VerifyEmail("a+b/c=="),
            parse("https://cubetimer.cc/verify-email?token=a%2Bb%2Fc%3D%3D")
        )
        assertEquals(
            AuthLink.VerifyEmail("abc"),
            parse("https://cubetimer.cc/verify-email?token=%20abc%20")
        )
    }

    @Test
    fun hostAndSchemeAreMatchedCaseInsensitivelyAndATrailingSlashIsAccepted() {
        assertEquals(
            AuthLink.VerifyEmail("t"),
            parse("HTTPS://CubeTimer.cc/verify-email/?token=t")
        )
    }

    @Test
    fun linksWithoutATokenAreIgnored() {
        assertNull(parse("https://cubetimer.cc/verify-email"))
        assertNull(parse("https://cubetimer.cc/verify-email?token="))
        assertNull(parse("https://cubetimer.cc/reset-password?token=%20"))
        assertNull(parse("https://cubetimer.cc/reset-password?code=abc"))
    }

    @Test
    fun otherHostsSchemesAndPathsAreIgnored() {
        assertNull(parse("https://evil.example/verify-email?token=t"))
        assertNull(parse("https://cubetimer.cc.evil.example/verify-email?token=t"))
        assertNull(parse("https://api.cubetimer.cc/verify-email?token=t"))
        assertNull(parse("http://cubetimer.cc/verify-email?token=t"))
        assertNull(parse("cubetimer://cubetimer.cc/verify-email?token=t"))
        assertNull(parse("https://cubetimer.cc/login?token=t"))
        assertNull(parse("https://cubetimer.cc/verify-email/extra?token=t"))
        assertNull(parse("https://cubetimer.cc/?token=t"))
        assertNull(parse("mailto:someone@cubetimer.cc?token=t"))
    }
}
