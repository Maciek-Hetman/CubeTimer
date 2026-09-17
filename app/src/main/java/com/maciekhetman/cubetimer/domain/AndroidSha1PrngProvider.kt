package com.maciekhetman.cubetimer.domain

import java.security.Provider
import java.security.SecureRandom
import java.security.SecureRandomSpi
import java.security.Security

/**
 * Provides a SHA1PRNG SecureRandom implementation for TNoodle on Android
 * where the SUN provider and legacy Harmony SHA1PRNG are unavailable.
 */
class AndroidSha1PrngProvider : Provider("SUN", 1.0, "Android SHA1PRNG provider for TNoodle") {

    init {
        put("SecureRandom.SHA1PRNG", AndroidSha1PrngSecureRandom::class.java.name)
    }

    /**
     * Delegates all randomness to the platform's own SecureRandom implementation (backed by
     * /dev/urandom via OpenSSL/Conscrypt on Android) instead of java.util.Random, whose 48-bit
     * internal state can only ever reach 2^48 of a 3x3 scramble's roughly 4.3e19 possible states.
     */
    class AndroidSha1PrngSecureRandom : SecureRandomSpi() {
        private val delegate: SecureRandom = resolvePlatformSecureRandom()

        override fun engineSetSeed(seed: ByteArray?) {
            if (seed != null) {
                delegate.setSeed(seed)
            }
        }

        override fun engineNextBytes(bytes: ByteArray?) {
            if (bytes != null) {
                delegate.nextBytes(bytes)
            }
        }

        override fun engineGenerateSeed(numBytes: Int): ByteArray {
            return delegate.generateSeed(numBytes)
        }

        companion object {
            /**
             * Returns a SecureRandom backed by a real platform provider, guarding against the
             * (normally impossible, since [install] appends this provider at the lowest priority)
             * case where resolving one would recurse back into this very class. Known-distinct
             * algorithms are tried first so the ambiguous no-arg `SecureRandom()` lookup - which is
             * where a recursive resolution could otherwise happen - is only used once we already
             * have reason to believe it will land on a different provider.
             */
            private fun resolvePlatformSecureRandom(): SecureRandom {
                for (algorithm in listOf("NativePRNG", "Windows-PRNG")) {
                    try {
                        val candidate = SecureRandom.getInstance(algorithm)
                        if (candidate.provider !is AndroidSha1PrngProvider) return candidate
                    } catch (_: Throwable) {
                        // Not available on this platform; try the next.
                    }
                }

                val default = SecureRandom()
                if (default.provider !is AndroidSha1PrngProvider) return default

                // Only reachable if literally no other SecureRandom implementation is registered
                // anywhere on the JVM, which should never happen on Android or in the Robolectric/JVM
                // test environment. Try one more explicit lookup before giving up and accepting it.
                try {
                    val strong = SecureRandom.getInstanceStrong()
                    if (strong.provider !is AndroidSha1PrngProvider) return strong
                } catch (_: Throwable) {
                    // No strong algorithm configured either; nothing left to try.
                }

                return default
            }
        }
    }

    companion object {
        @Volatile
        private var installed = false

        fun install() {
            if (installed) return
            synchronized(this) {
                if (installed) return
                try {
                    val sunAlreadyAvailable = try {
                        SecureRandom.getInstance("SHA1PRNG", "SUN") != null
                    } catch (_: Throwable) {
                        false
                    }

                    if (!sunAlreadyAvailable) {
                        Security.addProvider(AndroidSha1PrngProvider())
                    }

                    installed = true
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}
