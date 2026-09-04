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
        private val random = java.util.Random().apply {
            try {
                val urandom = java.io.File("/dev/urandom")
                if (urandom.exists() && urandom.canRead()) {
                    java.io.FileInputStream(urandom).use { stream ->
                        val seed = ByteArray(8)
                        val bytesRead = stream.read(seed)
                        if (bytesRead == 8) {
                            var s = 0L
                            for (b in seed) {
                                s = (s shl 8) or (b.toLong() and 0xffL)
                            }
                            setSeed(s)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fallback to default nanoTime-based seed in java.util.Random
            }
        }

        override fun engineSetSeed(seed: ByteArray?) {
            if (seed != null) {
                var s = 0L
                for (b in seed) {
                    s = (s shl 8) or (b.toLong() and 0xffL)
                }
                random.setSeed(s)
            }
        }

        override fun engineNextBytes(bytes: ByteArray?) {
            if (bytes != null) {
                random.nextBytes(bytes)
            }
        }

        override fun engineGenerateSeed(numBytes: Int): ByteArray {
            val seed = ByteArray(numBytes)
            engineNextBytes(seed)
            return seed
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
                        val sunProvider = AndroidSha1PrngProvider()
                        Security.addProvider(sunProvider)
                    }

                    val genericAvailable = try {
                        SecureRandom.getInstance("SHA1PRNG") != null
                    } catch (_: Throwable) {
                        false
                    }

                    if (!genericAvailable) {
                        val genericProvider = object : Provider("AndroidSHA1PRNG", 1.0, "Generic SHA1PRNG Provider") {
                            init {
                                put("SecureRandom.SHA1PRNG", AndroidSha1PrngSecureRandom::class.java.name)
                            }
                        }
                        Security.addProvider(genericProvider)
                    }

                    installed = true
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }
    }
}
