package com.sustream.tv.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key-value store for user credentials (Xtream passwords, backend tokens).
 *
 * Uses Jetpack [EncryptedSharedPreferences] backed by a per-app [MasterKey] stored in the Android
 * Keystore — hardware-backed on modern Fire TV hardware.
 *
 * Keys are plain strings; values are stored encrypted at rest and never written to logs
 * (see [com.sustream.tv.core.log.Redact] for the log-scrubbing policy).
 */
class CredentialStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "sustream_credentials"
    }
}
