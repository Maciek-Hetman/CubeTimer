package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * [TokenStorage] that keeps the refresh token and user identity in encrypted preferences.
 *
 * The secrets are never written to disk in the clear. If the encrypted file can't be opened (most
 * often a lost or invalidated Keystore master key) it is deleted and rebuilt, so the user just has
 * to sign in again; if encrypted storage can't be built at all, the session is held in memory only
 * ([isPersistent] is `false`) and is gone after the process dies. Read and write failures on an
 * open store are handled the same way instead of surfacing as crashes.
 *
 * The device id is not a secret and must stay stable so the server doesn't see a new device on
 * every launch, so it lives in its own plain preferences file ([deviceFileName]) and survives
 * resets, in-memory mode and [clearAuthData]; only [clearAll] removes it.
 */
class EncryptedTokenStorage(
    private val context: Context,
    private val prefFileName: String = PREFS_FILE_NAME,
    private val deviceFileName: String = DEVICE_PREFS_FILE_NAME,
    private val encryptedPreferencesFactory: EncryptedPreferencesFactory = AndroidEncryptedPreferencesFactory
) : TokenStorage {

    private val lock = Any()

    // In-memory access token (15-min TTL, never written to disk)
    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    override val accessTokenFlow: StateFlow<String?> = _accessTokenFlow.asStateFlow()

    // Opened lazily (under [lock]) so constructing this class is cheap: building
    // EncryptedSharedPreferences (MasterKey + Tink) only happens on first real access,
    // which is AuthManagerImpl.initialize() running on the IO dispatcher, not the caller's thread.
    private var securePrefs: SharedPreferences? = null
    private var persistent = false

    private val devicePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(deviceFileName, Context.MODE_PRIVATE)
    }

    /**
     * Whether the refresh token and identity survive a process restart. `false` means encrypted
     * storage is unavailable and the session is held in memory only.
     */
    val isPersistent: Boolean
        get() = synchronized(lock) {
            openSecurePrefs()
            persistent
        }

    override fun getAccessToken(): String? = _accessTokenFlow.value

    override fun setAccessToken(token: String?) {
        _accessTokenFlow.value = token
    }

    override fun getRefreshToken(): String? = secure { it.getString(KEY_REFRESH_TOKEN, null) }

    override fun getUserId(): String? = secure { it.getString(KEY_USER_ID, null) }

    override fun getUserEmail(): String? = secure { it.getString(KEY_USER_EMAIL, null) }

    override fun getUserRole(): String? = secure { it.getString(KEY_USER_ROLE, null) }

    override fun isUserEmailVerified(): Boolean = secure { it.getBoolean(KEY_USER_EMAIL_VERIFIED, false) }

    override fun getDisplayName(): String? = secure { it.getString(KEY_USER_DISPLAY_NAME, null) }

    override fun getCachedUser(): User? = secure { prefs ->
        val id = prefs.getString(KEY_USER_ID, null) ?: return@secure null
        val email = prefs.getString(KEY_USER_EMAIL, null) ?: return@secure null
        val displayName = prefs.getString(KEY_USER_DISPLAY_NAME, null)
        val roleStr = prefs.getString(KEY_USER_ROLE, null)
        val verified = prefs.getBoolean(KEY_USER_EMAIL_VERIFIED, false)
        User(
            id = id,
            email = email,
            displayName = displayName,
            emailVerified = verified,
            userRole = UserRole.fromString(roleStr)
        )
    }

    override fun saveAuthSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        userEmail: String,
        userRole: String,
        emailVerified: Boolean,
        displayName: String?
    ) {
        setAccessToken(accessToken)
        secure {
            it.edit {
                putString(KEY_REFRESH_TOKEN, refreshToken)
                putString(KEY_USER_ID, userId)
                putString(KEY_USER_EMAIL, userEmail)
                putString(KEY_USER_ROLE, userRole)
                putBoolean(KEY_USER_EMAIL_VERIFIED, emailVerified)
                if (displayName != null) {
                    putString(KEY_USER_DISPLAY_NAME, displayName)
                } else {
                    remove(KEY_USER_DISPLAY_NAME)
                }
            }
        }
    }

    override fun getDeviceId(): String = synchronized(lock) {
        // Opening the secure store first migrates a device id that predates the separate device
        // file; without it we'd mint a new id for a device the server already knows.
        openSecurePrefs()
        var deviceId = devicePrefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            devicePrefs.edit { putString(KEY_DEVICE_ID, deviceId) }
        }
        deviceId
    }

    override fun clearAuthData() {
        setAccessToken(null)
        secure {
            it.edit {
                remove(KEY_REFRESH_TOKEN)
                remove(KEY_USER_ID)
                remove(KEY_USER_EMAIL)
                remove(KEY_USER_ROLE)
                remove(KEY_USER_EMAIL_VERIFIED)
                remove(KEY_USER_DISPLAY_NAME)
            }
        }
    }

    override fun clearAll() {
        setAccessToken(null)
        synchronized(lock) {
            secure { it.edit { clear() } }
            devicePrefs.edit { clear() }
        }
    }

    /**
     * Runs [block] against the secure store. If the store throws (undecryptable value, dead
     * Keystore key, ...), it is reset and [block] runs once more against the fresh store: reads
     * then see no session, writes land in the new store. Should even that fail, the session moves
     * to memory.
     */
    private fun <T> secure(block: (SharedPreferences) -> T): T = synchronized(lock) {
        try {
            block(openSecurePrefs())
        } catch (e: Exception) {
            Log.w(TAG, "Secure preferences access failed. Resetting the store.", e)
            try {
                block(resetSecurePrefs())
            } catch (e2: Exception) {
                Log.e(TAG, "Secure preferences still failing after reset. Keeping the session in memory.", e2)
                block(switchToMemory())
            }
        }
    }

    // --- Secure store lifecycle. Everything below runs with [lock] held. ---

    private fun openSecurePrefs(): SharedPreferences {
        securePrefs?.let { return it }
        val legacyDeviceId = scrubLegacyPlaintext()
        val prefs = buildSecurePrefs()
        securePrefs = prefs
        migrateDeviceId(prefs, legacyDeviceId)
        return prefs
    }

    private fun resetSecurePrefs(): SharedPreferences {
        deleteSecureFile()
        return buildSecurePrefs().also { securePrefs = it }
    }

    private fun switchToMemory(): SharedPreferences {
        deleteSecureFile()
        persistent = false
        return InMemorySharedPreferences().also { securePrefs = it }
    }

    /**
     * Opens the encrypted file. If that fails the file is reset once and retried; if encrypted
     * storage still can't be built the result is an [InMemorySharedPreferences] - never a plain
     * file, which would leave the refresh token readable on disk.
     */
    private fun buildSecurePrefs(): SharedPreferences {
        try {
            return encryptedPreferencesFactory.create(context, prefFileName).also { persistent = true }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to open encrypted preferences. Resetting them.", e)
        }

        deleteSecureFile()

        try {
            return encryptedPreferencesFactory.create(context, prefFileName).also { persistent = true }
        } catch (e: Throwable) {
            Log.e(TAG, "Encrypted preferences unavailable. Keeping the session in memory only.", e)
        }

        persistent = false
        return InMemorySharedPreferences()
    }

    /**
     * Deletes the secure preferences file. This must go through [Context.deleteSharedPreferences]:
     * the platform caches every open preferences file per process, so removing the XML by hand
     * leaves the (corrupt) contents in the cache and a rebuild just reads them again.
     */
    private fun deleteSecureFile() {
        try {
            if (!context.deleteSharedPreferences(prefFileName)) {
                Log.w(TAG, "Could not delete preferences file $prefFileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete preferences file $prefFileName", e)
        }
    }

    /**
     * Earlier versions fell back to plain [Context.getSharedPreferences] on the same file when
     * encrypted storage was unavailable, leaving the refresh token and identity readable on disk -
     * and, if encryption came back later, alongside a working encrypted store. Removes those
     * values. Entries of a real encrypted file are stored under encrypted key names, so removing
     * these exact plain names can't touch them. Returns the device id found there, if any.
     */
    private fun scrubLegacyPlaintext(): String? {
        return try {
            val raw = context.getSharedPreferences(prefFileName, Context.MODE_PRIVATE)
            val leaked = PLAINTEXT_KEYS.filter { raw.contains(it) }
            if (leaked.isEmpty()) return null

            val legacyDeviceId = raw.getString(KEY_DEVICE_ID, null)
            raw.edit(commit = true) { leaked.forEach { remove(it) } }
            legacyDeviceId
        } catch (e: Exception) {
            Log.w(TAG, "Could not scrub legacy plaintext preferences", e)
            null
        }
    }

    /**
     * The device id used to live in the secure file (or, after a fallback, in plaintext next to
     * it). Moves it to the device file, keeping an id that is already there.
     */
    private fun migrateDeviceId(secure: SharedPreferences, legacyPlaintextDeviceId: String?) {
        try {
            val secureDeviceId = secure.getString(KEY_DEVICE_ID, null)
            val migrated = secureDeviceId?.takeIf { it.isNotBlank() }
                ?: legacyPlaintextDeviceId?.takeIf { it.isNotBlank() }
            if (migrated != null && devicePrefs.getString(KEY_DEVICE_ID, null).isNullOrBlank()) {
                devicePrefs.edit { putString(KEY_DEVICE_ID, migrated) }
            }
            if (secureDeviceId != null) {
                secure.edit { remove(KEY_DEVICE_ID) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not migrate the device id", e)
        }
    }

    companion object {
        private const val TAG = "EncryptedTokenStorage"
        const val PREFS_FILE_NAME = "cubetimer_secure_prefs"
        const val DEVICE_PREFS_FILE_NAME = "cubetimer_device_prefs"

        private const val KEY_REFRESH_TOKEN = "key_refresh_token"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_USER_EMAIL = "key_user_email"
        private const val KEY_USER_ROLE = "key_user_role"
        private const val KEY_USER_EMAIL_VERIFIED = "key_user_email_verified"
        private const val KEY_USER_DISPLAY_NAME = "key_user_display_name"
        private const val KEY_DEVICE_ID = "key_device_id"

        private val PLAINTEXT_KEYS = listOf(
            KEY_REFRESH_TOKEN,
            KEY_USER_ID,
            KEY_USER_EMAIL,
            KEY_USER_ROLE,
            KEY_USER_EMAIL_VERIFIED,
            KEY_USER_DISPLAY_NAME,
            KEY_DEVICE_ID
        )
    }
}
