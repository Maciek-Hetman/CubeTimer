package com.maciekhetman.cubetimer.domain

import com.maciekhetman.cubetimer.model.Mode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

class ScrambleGeneratorTest {

    @Before
    fun setup() {
        AndroidSha1PrngProvider.install()
    }

    @Test
    fun testSunProviderResolvesSha1Prng() {
        val srWithSun = SecureRandom.getInstance("SHA1PRNG", "SUN")
        assertNotNull(srWithSun)
        val bytes = ByteArray(16)
        srWithSun.nextBytes(bytes)
        assertTrue(bytes.any { it != 0.toByte() })
    }

    @Test
    fun testGenericProviderResolvesSha1Prng() {
        val srGeneric = SecureRandom.getInstance("SHA1PRNG")
        assertNotNull(srGeneric)
        val bytes = ByteArray(16)
        srGeneric.nextBytes(bytes)
        assertTrue(bytes.any { it != 0.toByte() })
    }

    @Test
    fun testGenerateScrambleForAllModes() {
        for (mode in Mode.entries) {
            val scramble = ScrambleGenerator.generateScramble(mode)
            assertNotNull("Scramble for $mode must not be null", scramble)
            assertFalse("Scramble for $mode must not be blank", scramble.isBlank())
            assertTrue("Scramble for $mode must contain valid notation moves", scramble.length > 5)
        }
    }
}
