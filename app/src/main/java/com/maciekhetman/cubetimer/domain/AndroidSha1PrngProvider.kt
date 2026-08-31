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

    class AndroidSha1PrngSecureRandom : SecureRandomSpi() {
        private val delegate = SecureRandom()

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
    }

    companion object {
        @Volatile
        private var installed = false

        fun install() {
            if (installed) return
            synchronized(this) {
                if (installed) return
                try {
                    Security.removeProvider("SUN")
                    val sunProvider = AndroidSha1PrngProvider()
                    Security.insertProviderAt(sunProvider, 1)

                    val genericProvider = object : Provider("AndroidSHA1PRNG", 1.0, "Generic SHA1PRNG Provider") {
                        init {
                            put("SecureRandom.SHA1PRNG", AndroidSha1PrngSecureRandom::class.java.name)
                        }
                    }
                    Security.addProvider(genericProvider)
                    installed = true
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}
