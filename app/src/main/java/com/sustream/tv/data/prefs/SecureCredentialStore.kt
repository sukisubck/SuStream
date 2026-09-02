package com.sustream.tv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sustream.tv.core.log.AppLog
import com.sustream.tv.core.log.Secret

private const val TAG = "SecureStore"

/**
 * Encrypted store for the only three kinds of secret this app holds: Xtream playlist passwords,
 * the provider API key in debug direct mode, and the backend refresh token.
 *
 * Why `EncryptedSharedPreferences` rather than plain preferences: on a rooted or ADB-enabled Fire TV
 * — and sideloading over ADB is the normal way apps get onto one — the app's private directory is
 * readable. A plain preference file would hand over a user's live IPTV subscription in cleartext.
 * The master key is held in the Android keystore, so the ciphertext is useless without the device.
 *
 * Two deliberate design points:
 *
 *  * **Everything goes in and out as [Secret]**, whose `toString()` is `"Secret(***)"`. A password
 *    handled as a plain `String` leaks the first time any data class containing it is logged.
 *  * **Keystore failure is survivable.** `MasterKey` creation can fail on devices with a broken or
 *    reset keystore, and a hard crash at startup would make the app unusable rather than merely
 *    degraded. On failure the store reports itself unavailable, IPTV asks for credentials again, and
 *    Diagnostics explains why — see [isAvailable].
 */
class SecureCredentialStore(
    context: Context,
) {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (throwable: Throwable) {
        // Deliberately broad: keystore failures surface as GeneralSecurityException, IOException,
        // and on some OEM builds as an Error. None of them should take the app down.
        AppLog.e(TAG, "Encrypted store unavailable; credentials cannot be persisted", throwable)
        null
    }

    /** False when the keystore is unusable. Surfaced in Diagnostics rather than failing silently. */
    val isAvailable: Boolean get() = prefs != null

    // ---- Xtream playlist credentials ---------------------------------------

    fun putPlaylistPassword(playlistId: String, password: Secret) {
        write(playlistPasswordKey(playlistId), password)
    }

    fun playlistPassword(playlistId: String): Secret? = read(playlistPasswordKey(playlistId))

    fun removePlaylistPassword(playlistId: String) {
        remove(playlistPasswordKey(playlistId))
    }

    // ---- Provider credentials ----------------------------------------------

    fun putProviderApiKey(provider: String, key: Secret) {
        write(providerKey(provider), key)
    }

    fun providerApiKey(provider: String): Secret? = read(providerKey(provider))

    fun removeProviderApiKey(provider: String) {
        remove(providerKey(provider))
    }

    // ---- Session -----------------------------------------------------------

    /**
     * The refresh token is the one credential that must survive a restart, so it is persisted here.
     *
     * The *access* token is deliberately not stored at all: it lives in memory for its fifteen-minute
     * life, and writing a short-lived credential to disk buys nothing while widening the exposure.
     */
    fun putRefreshToken(token: Secret) {
        write(KEY_REFRESH_TOKEN, token)
    }

    fun refreshToken(): Secret? = read(KEY_REFRESH_TOKEN)

    fun removeRefreshToken() {
        remove(KEY_REFRESH_TOKEN)
    }

    // ---- Wipe --------------------------------------------------------------

    /** Called on sign-out and by Settings -> Reset local data. */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }

    /** Key names only, for the Diagnostics report. Never values. */
    fun storedKeyNames(): List<String> =
        prefs?.all?.keys?.sorted() ?: emptyList()

    // ---- Internals ---------------------------------------------------------

    private fun write(key: String, value: Secret) {
        val store = prefs ?: return
        if (value.isBlank) {
            store.edit().remove(key).apply()
            return
        }
        store.edit().putString(key, value.reveal()).apply()
    }

    private fun read(key: String): Secret? {
        val raw = prefs?.getString(key, null) ?: return null
        return if (raw.isEmpty()) null else Secret(raw)
    }

    private fun remove(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    private fun playlistPasswordKey(playlistId: String) = PREFIX_PLAYLIST_PASSWORD + playlistId

    private fun providerKey(provider: String) = PREFIX_PROVIDER_KEY + provider.lowercase()

    private companion object {
        const val PREFS_NAME = "sustream_secure_prefs"
        const val MASTER_KEY_ALIAS = "sustream_master_key"
        const val PREFIX_PLAYLIST_PASSWORD = "playlist_password_"
        const val PREFIX_PROVIDER_KEY = "provider_key_"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
