package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.maciekhetman.cubetimer.model.User
import com.maciekhetman.cubetimer.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class EncryptedTokenStorage(
    private val context: Context,
    prefFileName: String = PREFS_FILE_NAME
) : TokenStorage {

    private val lock = Any()

    // In-memory access token (15-min TTL, never written to disk)
    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    override val accessTokenFlow: StateFlow<String?> = _accessTokenFlow.asStateFlow()

    private val prefs: SharedPreferences = createPreferences(context, prefFileName)

    override fun getAccessToken(): String? = _accessTokenFlow.value

    override fun setAccessToken(token: String?) {
        _accessTokenFlow.value = token
    }

    override fun getRefreshToken(): String? = synchronized(lock) {
        prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    override fun setRefreshToken(token: String?): Unit = synchronized(lock) {
        prefs.edit().apply {
            if (token != null) {
                putString(KEY_REFRESH_TOKEN, token)
            } else {
                remove(KEY_REFRESH_TOKEN)
            }
        }.apply()
    }

    override fun getUserId(): String? = synchronized(lock) {
        prefs.getString(KEY_USER_ID, null)
    }

    override fun getUserEmail(): String? = synchronized(lock) {
        prefs.getString(KEY_USER_EMAIL, null)
    }

    override fun getUserRole(): String? = synchronized(lock) {
        prefs.getString(KEY_USER_ROLE, null)
    }

    override fun isUserEmailVerified(): Boolean = synchronized(lock) {
        prefs.getBoolean(KEY_USER_EMAIL_VERIFIED, false)
    }

    override fun getDisplayName(): String? = synchronized(lock) {
        prefs.getString(KEY_USER_DISPLAY_NAME, null)
    }

    override fun getCachedUser(): User? = synchronized(lock) {
        val id = prefs.getString(KEY_USER_ID, null) ?: return@synchronized null
        val email = prefs.getString(KEY_USER_EMAIL, null) ?: return@synchronized null
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
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_USER_EMAIL, userEmail)
                .putString(KEY_USER_ROLE, userRole)
                .putBoolean(KEY_USER_EMAIL_VERIFIED, emailVerified)
                .apply {
                    if (displayName != null) {
                        putString(KEY_USER_DISPLAY_NAME, displayName)
                    } else {
                        remove(KEY_USER_DISPLAY_NAME)
                    }
                }
                .apply()
        }
    }

    override fun saveUser(user: User) {
        synchronized(lock) {
            prefs.edit()
                .putString(KEY_USER_ID, user.id)
                .putString(KEY_USER_EMAIL, user.email)
                .putString(KEY_USER_ROLE, user.userRole.name.lowercase())
                .putBoolean(KEY_USER_EMAIL_VERIFIED, user.emailVerified)
                .apply {
                    if (user.displayName != null) {
                        putString(KEY_USER_DISPLAY_NAME, user.displayName)
                    } else {
                        remove(KEY_USER_DISPLAY_NAME)
                    }
                }
                .apply()
        }
    }

    override fun getDeviceId(): String = synchronized(lock) {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrBlank()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        deviceId
    }

    override fun clearAuthData() {
        setAccessToken(null)
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_USER_ROLE)
                .remove(KEY_USER_EMAIL_VERIFIED)
                .remove(KEY_USER_DISPLAY_NAME)
                .apply()
        }
    }

    override fun clearAll() {
        setAccessToken(null)
        synchronized(lock) {
            prefs.edit().clear().apply()
        }
    }

    companion object {
        private const val TAG = "EncryptedTokenStorage"
        const val PREFS_FILE_NAME = "cubetimer_secure_prefs"

        private const val KEY_REFRESH_TOKEN = "key_refresh_token"
        private const val KEY_USER_ID = "key_user_id"
        private const val KEY_USER_EMAIL = "key_user_email"
        private const val KEY_USER_ROLE = "key_user_role"
        private const val KEY_USER_EMAIL_VERIFIED = "key_user_email_verified"
        private const val KEY_USER_DISPLAY_NAME = "key_user_display_name"
        private const val KEY_DEVICE_ID = "key_device_id"

        private fun createPreferences(context: Context, fileName: String): SharedPreferences {
            return try {
                buildEncryptedPreferences(context, fileName)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to initialize EncryptedSharedPreferences. Attempting reset or fallback.", e)
                try {
                    deletePreferencesFile(context, fileName)
                    buildEncryptedPreferences(context, fileName)
                } catch (e2: Throwable) {
                    Log.e(TAG, "EncryptedSharedPreferences unavailable. Falling back to standard SharedPreferences.", e2)
                    context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                }
            }
        }

        private fun buildEncryptedPreferences(context: Context, fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun deletePreferencesFile(context: Context, fileName: String) {
            try {
                val filesDir = context.filesDir
                val sharedPrefsDir = if (filesDir != null && filesDir.parentFile != null) {
                    File(filesDir.parentFile, "shared_prefs")
                } else {
                    File(context.cacheDir, "shared_prefs")
                }
                val prefsFile = File(sharedPrefsDir, "$fileName.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to delete corrupted preferences file", ex)
            }
        }
    }
}
