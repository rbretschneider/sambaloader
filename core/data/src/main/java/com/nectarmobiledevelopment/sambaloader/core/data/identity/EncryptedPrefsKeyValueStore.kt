package com.nectarmobiledevelopment.sambaloader.core.data.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [SecureKeyValueStore] backed by EncryptedSharedPreferences with an
 * AndroidKeyStore master key. Defense in depth: everything stored is public
 * certificate material, but there is no reason to leave it world-plaintext.
 */
class EncryptedPrefsKeyValueStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecureKeyValueStore {

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? {
        return preferences.getString(key, null)
    }

    override fun put(entries: Map<String, String>) {
        val editor = preferences.edit()
        for ((key, value) in entries) {
            editor.putString(key, value)
        }
        editor.apply()
    }

    override fun remove(keys: Set<String>) {
        val editor = preferences.edit()
        for (key in keys) {
            editor.remove(key)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "sambaloader-identity"
    }
}
