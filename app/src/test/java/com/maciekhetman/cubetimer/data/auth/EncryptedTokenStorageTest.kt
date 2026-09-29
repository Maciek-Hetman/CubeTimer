package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Covers how [EncryptedTokenStorage] copes when encrypted storage misbehaves: a corrupt file is
 * reset (for real, including the platform's per-process cache of it), and when encryption cannot
 * be had at all the session lives in memory - never in a plaintext file. The Keystore itself can't
 * be reached under Robolectric, so the encrypted store is replaced by hand-written factories.
 *
 * Design adapted from the parked `EncryptedTokenStorageTest` (see the coordinator's brief).
 */
@RunWith(RobolectricTestRunner::class)
class EncryptedTokenStorageTest {

    private lateinit var context: Context
    private lateinit var secureName: String
    private lateinit var deviceName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        secureName = "secure_$suffix"
        deviceName = "device_$suffix"
    }

    private fun storage(factory: EncryptedPreferencesFactory) =
        EncryptedTokenStorage(context, secureName, deviceName, factory)

    private fun rawPrefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val sharedPrefsDir: File
        get() = File(context.applicationInfo.dataDir, "shared_prefs")

    private val secureFile: File
        get() = File(sharedPrefsDir, "$secureName.xml")

    private fun EncryptedTokenStorage.signIn(
        refreshToken: String,
        userId: String = "u1",
        email: String = "u1@example.com",
        displayName: String? = null
    ) = saveAuthSession(
        accessToken = "access-token",
        refreshToken = refreshToken,
        userId = userId,
        userEmail = email,
        userRole = "user",
        emailVerified = true,
        displayName = displayName
    )

    /** Every SharedPreferences file on disk plus the in-process maps of the files we know about. */
    private fun allPersistedPrefValues(): String {
        val onDisk = sharedPrefsDir.listFiles().orEmpty().joinToString("\n") { it.readText() }
        val inProcess = sharedPrefsDir.listFiles().orEmpty()
            .map { it.name.removeSuffix(".xml") }
            .plus(listOf(secureName, deviceName))
            .joinToString("\n") { rawPrefs(it).all.toString() }
        return onDisk + "\n" + inProcess
    }

    // --- A corrupt encrypted file is reset, and the reset really takes effect ---

    @Test
    fun `corrupt encrypted file is reset and rebuilt, evicting the platform cache`() {
        // Simulates a keyset that can no longer be decrypted: the factory refuses the file for as
        // long as the marker is in it. Only a reset that also evicts the SharedPreferences instance
        // Android caches per process makes the marker disappear; deleting the XML by hand leaves
        // the cached copy - marker included - in place and the retry fails the same way.
        rawPrefs(secureName).edit().putString(CORRUPT_MARKER, "garbage").commit()
        assertTrue(secureFile.exists())
        val factory = CorruptibleFactory()
        val storage = storage(factory)

        storage.signIn(refreshToken = "refresh-after-reset")

        assertEquals("first attempt on the corrupt file, second after the reset", 2, factory.creations)
        assertTrue(storage.isPersistent)
        assertFalse(rawPrefs(secureName).contains(CORRUPT_MARKER))
        assertEquals("refresh-after-reset", storage.getRefreshToken())
        assertEquals("u1", storage.getCachedUser()?.id)
    }

    @Test
    fun `plaintext left in a corrupt file by the old fallback is gone after the reset`() {
        rawPrefs(secureName).edit()
            .putString(CORRUPT_MARKER, "garbage")
            .putString("key_refresh_token", "leaked-refresh-token")
            .putString("key_user_email", "leaked@example.com")
            .commit()
        val storage = storage(CorruptibleFactory())

        storage.signIn(refreshToken = "fresh-refresh-token")

        assertTrue(storage.isPersistent)
        val persisted = allPersistedPrefValues()
        assertFalse(persisted.contains("leaked-refresh-token"))
        assertFalse(persisted.contains("leaked@example.com"))
    }

    @Test
    fun `reset that still cannot build encryption ends in memory and leaves no file behind`() {
        rawPrefs(secureName).edit()
            .putString("key_refresh_token", "leaked-refresh-token")
            .commit()
        val storage = storage(UnavailableEncryptedPreferences)

        storage.signIn(refreshToken = "session-refresh-token")

        assertFalse(storage.isPersistent)
        assertEquals("session-refresh-token", storage.getRefreshToken())
        assertFalse("the plaintext file must be deleted, not rewritten", secureFile.exists())
        assertFalse(allPersistedPrefValues().contains("leaked-refresh-token"))
        assertFalse(allPersistedPrefValues().contains("session-refresh-token"))
    }

    @Test
    fun `default factory never leaves the refresh token readable on disk`() {
        // Whether the Keystore is reachable here or not, the token is either encrypted or in memory.
        val storage = EncryptedTokenStorage(context, secureName, deviceName)

        storage.signIn(refreshToken = "refresh-secret-4c1d", email = "secret-mail@example.com")

        assertEquals("refresh-secret-4c1d", storage.getRefreshToken())
        val persisted = allPersistedPrefValues()
        assertFalse(persisted.contains("refresh-secret-4c1d"))
        assertFalse(persisted.contains("secret-mail@example.com"))
    }

    // --- Memory-only fallback ---

    @Test
    fun `fallback mode keeps refresh token and identity off disk`() {
        val storage = storage(UnavailableEncryptedPreferences)

        storage.saveAuthSession(
            accessToken = "access-secret",
            refreshToken = "refresh-secret-7f3a",
            userId = "user-secret-id-91",
            userEmail = "secret-mail@example.com",
            userRole = "user",
            emailVerified = true,
            displayName = "Secret Name"
        )
        storage.getDeviceId()

        assertFalse(storage.isPersistent)
        // Still usable for this process.
        assertEquals("refresh-secret-7f3a", storage.getRefreshToken())
        assertEquals("user-secret-id-91", storage.getCachedUser()?.id)
        assertTrue(!storage.getRefreshToken().isNullOrBlank())

        val persisted = allPersistedPrefValues()
        listOf("refresh-secret-7f3a", "user-secret-id-91", "secret-mail@example.com", "Secret Name", "access-secret")
            .forEach { secret -> assertFalse("'$secret' leaked to disk", persisted.contains(secret)) }
        assertTrue(rawPrefs(secureName).all.isEmpty())
    }

    @Test
    fun `fallback mode session does not survive a new instance`() {
        storage(UnavailableEncryptedPreferences).signIn(refreshToken = "refresh-1")

        val restarted = storage(UnavailableEncryptedPreferences)
        assertNull(restarted.getRefreshToken())
        assertNull(restarted.getCachedUser())
        assertFalse(restarted.isAuthenticated())
    }

    @Test
    fun `clearAuthData and clearAll work in fallback mode`() {
        val storage = storage(UnavailableEncryptedPreferences)
        storage.signIn(refreshToken = "refresh-1")
        val deviceId = storage.getDeviceId()

        storage.clearAuthData()
        assertNull(storage.getRefreshToken())
        assertNull(storage.getAccessToken())
        assertEquals(deviceId, storage.getDeviceId())

        storage.signIn(refreshToken = "refresh-2")
        storage.clearAll()
        assertNull(storage.getRefreshToken())
        assertTrue(storage.getDeviceId() != deviceId)
    }

    // --- Device id ---

    @Test
    fun `device id is stable across instances in fallback mode and survives clearAuthData`() {
        val first = storage(UnavailableEncryptedPreferences)
        val id = first.getDeviceId()
        first.signIn(refreshToken = "r")
        first.clearAuthData()
        assertEquals(id, first.getDeviceId())

        val second = storage(UnavailableEncryptedPreferences)
        assertEquals(id, second.getDeviceId())
        assertEquals(id, rawPrefs(deviceName).getString("key_device_id", null))
    }

    @Test
    fun `device id stays in device prefs when encrypted store works and survives restart`() {
        val factory = FakeEncryptedFactory()
        val id = storage(factory).getDeviceId()

        assertEquals(id, storage(factory).getDeviceId())
        assertEquals(id, rawPrefs(deviceName).getString("key_device_id", null))
        assertFalse(factory.backing(context, secureName).contains("key_device_id"))
    }

    @Test
    fun `existing device id in the encrypted file is migrated once`() {
        val factory = FakeEncryptedFactory()
        factory.backing(context, secureName).edit()
            .putString("key_device_id", "existing-device-id")
            .putString("key_refresh_token", "refresh-kept")
            .commit()

        val storage = storage(factory)
        assertEquals("existing-device-id", storage.getDeviceId())
        assertEquals("refresh-kept", storage.getRefreshToken())
        assertTrue(storage.isPersistent)
        assertEquals("existing-device-id", rawPrefs(deviceName).getString("key_device_id", null))
        assertFalse(factory.backing(context, secureName).contains("key_device_id"))

        assertEquals("existing-device-id", storage(factory).getDeviceId())
    }

    @Test
    fun `device id is kept when the encrypted file has to be reset`() {
        val factory = CorruptibleFactory()
        val id = storage(factory).getDeviceId()
        rawPrefs(secureName).edit().putString(CORRUPT_MARKER, "garbage").commit()

        val storage = storage(factory)
        storage.signIn(refreshToken = "refresh-after-reset")

        assertEquals(id, storage.getDeviceId())
    }

    // --- Plaintext left behind by the old fallback ---

    @Test
    fun `legacy plaintext values are removed on init and the device id is kept`() {
        rawPrefs(secureName).edit()
            .putString("key_refresh_token", "leaked-refresh")
            .putString("key_user_id", "leaked-user")
            .putString("key_user_email", "leaked@example.com")
            .putString("key_user_role", "admin")
            .putBoolean("key_user_email_verified", true)
            .putString("key_device_id", "legacy-device-id")
            .commit()

        val storage = storage(UnavailableEncryptedPreferences)

        assertNull(storage.getRefreshToken())
        assertNull(storage.getCachedUser())
        assertTrue(rawPrefs(secureName).all.isEmpty())
        assertFalse(allPersistedPrefValues().contains("leaked-refresh"))
        assertEquals("legacy-device-id", storage.getDeviceId())
    }

    @Test
    fun `plaintext cleanup leaves encrypted entries of a healthy file untouched`() {
        val keysetKey = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        val cipherKey = "AUx2Y2lwaGVydGV4dC1rZXk="
        rawPrefs(secureName).edit()
            .putString(keysetKey, "keyset-blob")
            .putString(cipherKey, "ciphertext-blob")
            .putString("key_refresh_token", "leaked-refresh")
            .commit()

        val storage = storage(FakeEncryptedFactory())
        assertTrue(storage.isPersistent)

        val raw = rawPrefs(secureName).all
        assertEquals("keyset-blob", raw[keysetKey])
        assertEquals("ciphertext-blob", raw[cipherKey])
        assertFalse(raw.containsKey("key_refresh_token"))
    }

    // --- Failures of an already open store ---

    @Test
    fun `throwing read yields no session instead of an exception and rebuilds the store`() {
        val factory = FlakyFactory()
        val storage = storage(factory)
        storage.signIn(refreshToken = "refresh-1", displayName = "N")
        assertEquals(1, factory.created.size)

        factory.created.single().failReads = true

        assertNull(storage.getRefreshToken())
        assertNull(storage.getCachedUser())
        assertFalse(storage.isAuthenticated())
        assertEquals(2, factory.created.size)

        // The rebuilt store is usable again.
        storage.signIn(refreshToken = "refresh-2", userId = "u2", email = "u2@example.com")
        assertEquals("refresh-2", storage.getRefreshToken())
        assertEquals("u2", storage.getCachedUser()?.id)
    }

    @Test
    fun `throwing read with encryption gone for good falls back to memory`() {
        val factory = FlakyFactory(maxCreations = 1)
        val storage = storage(factory)
        storage.signIn(refreshToken = "refresh-1")
        factory.created.single().failReads = true

        assertNull(storage.getRefreshToken())
        assertFalse(storage.isPersistent)

        storage.signIn(refreshToken = "refresh-mem")
        assertEquals("refresh-mem", storage.getRefreshToken())
        assertFalse(allPersistedPrefValues().contains("refresh-mem"))
    }

    @Test
    fun `throwing write is not fatal and the value lands in the rebuilt store`() {
        val factory = FlakyFactory()
        val storage = storage(factory)
        storage.getRefreshToken() // open the store
        factory.created.single().failWrites = true

        storage.signIn(refreshToken = "refresh-after-failure")

        assertEquals("refresh-after-failure", storage.getRefreshToken())
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `device id read does not depend on a broken encrypted store`() {
        val factory = FlakyFactory()
        val storage = storage(factory)
        val id = storage.getDeviceId()
        factory.created.single().failReads = true

        assertNull(storage.getRefreshToken())
        assertEquals(id, storage.getDeviceId())
    }

    // --- Fakes ---

    /** Marks a file the [CorruptibleFactory] refuses to open, like an undecryptable keyset. */
    private companion object {
        const val CORRUPT_MARKER = "__corrupt_keyset__"
    }

    /** Encrypted preferences that can never be built (like Robolectric / a broken Keystore). */
    private object UnavailableEncryptedPreferences : EncryptedPreferencesFactory {
        override fun create(context: Context, fileName: String): SharedPreferences =
            throw java.security.KeyStoreException("AndroidKeyStore not available")
    }

    /**
     * Opens the very file it is asked for, unless [CORRUPT_MARKER] is in it - then it fails the way
     * EncryptedSharedPreferences does on a keyset it can't decrypt. It goes through
     * [Context.getSharedPreferences], so it sees exactly what the platform cache holds.
     */
    private class CorruptibleFactory : EncryptedPreferencesFactory {
        var creations = 0

        override fun create(context: Context, fileName: String): SharedPreferences {
            creations++
            val file = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            if (file.contains(CORRUPT_MARKER)) throw GeneralSecurityException("keyset cannot be decrypted")
            return file
        }
    }

    /**
     * Stands in for a working encrypted store. Backed by a separate plain file so its contents
     * survive "restarts" (new instances) and never overlap the raw secure-file checks.
     */
    private class FakeEncryptedFactory : EncryptedPreferencesFactory {
        fun backing(context: Context, fileName: String): SharedPreferences =
            context.getSharedPreferences("${fileName}_fake_encrypted", Context.MODE_PRIVATE)

        override fun create(context: Context, fileName: String): SharedPreferences =
            backing(context, fileName)
    }

    /** Hands out in-memory stores whose reads/writes can be made to throw like a dead Keystore. */
    private class FlakyFactory(private val maxCreations: Int = Int.MAX_VALUE) : EncryptedPreferencesFactory {
        val created = mutableListOf<FlakyPrefs>()

        override fun create(context: Context, fileName: String): SharedPreferences {
            if (created.size >= maxCreations) throw java.security.KeyStoreException("key permanently invalidated")
            return FlakyPrefs().also { created += it }
        }
    }

    private class FlakyPrefs(
        private val delegate: SharedPreferences = InMemorySharedPreferences()
    ) : SharedPreferences by delegate {
        @Volatile var failReads = false
        @Volatile var failWrites = false

        private fun checkRead() {
            if (failReads) throw SecurityException("Could not decrypt value")
        }

        override fun getString(key: String?, defValue: String?): String? {
            checkRead()
            return delegate.getString(key, defValue)
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            checkRead()
            return delegate.getBoolean(key, defValue)
        }

        override fun getAll(): Map<String, *> {
            checkRead()
            return delegate.all
        }

        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            if (!failWrites) return editor
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                    throw SecurityException("Could not encrypt value")
            }
        }
    }
}
