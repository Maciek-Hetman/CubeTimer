package com.maciekhetman.cubetimer.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Builds the encrypted preferences file [EncryptedTokenStorage] keeps the refresh token and user
 * identity in. It is a seam so tests can simulate Keystore failures, which real
 * [EncryptedSharedPreferences] can neither reproduce on demand nor run at all under Robolectric.
 *
 * Implementations throw if the file cannot be opened (missing or invalidated master key, corrupt
 * keyset, no Keystore); [EncryptedTokenStorage] decides what to do about it.
 */
fun interface EncryptedPreferencesFactory {
    fun create(context: Context, fileName: String): SharedPreferences
}

/** The real thing: AES-256 [EncryptedSharedPreferences] under an AndroidKeyStore master key. */
object AndroidEncryptedPreferencesFactory : EncryptedPreferencesFactory {
    override fun create(context: Context, fileName: String): SharedPreferences {
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
}
