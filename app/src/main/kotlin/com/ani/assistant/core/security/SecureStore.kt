package com.ani.assistant.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ani.assistant.core.log.AniLog

/**
 * Encrypted storage for the few values that genuinely need it.
 *
 * Ani has no API keys on the device by design — those live on the backend — so the only
 * things here are integration tokens the user themselves authorised (a Spotify OAuth
 * token, for example) and the backend's per-install identifier.
 *
 * Backed by [EncryptedSharedPreferences], whose master key sits in the Android Keystore
 * and therefore cannot be extracted from a rooted filesystem dump.
 *
 * If keystore initialisation fails — which happens on a small number of OEM builds with
 * broken keystore implementations, and after a keystore reset — the store degrades to
 * *refusing to persist* rather than silently falling back to plaintext. Losing a token is
 * an inconvenience; writing it unencrypted after promising otherwise is a breach.
 */
class SecureStore(context: Context) {

    private val preferences: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (error: Exception) {
        AniLog.e(TAG, "keystore unavailable; secure values will not be persisted", error)
        null
    }

    /** True when encrypted storage is working. Surfaced in Diagnostics. */
    val isAvailable: Boolean get() = preferences != null

    fun put(key: String, value: String) {
        val store = preferences ?: return
        store.edit { putString(key, value) }
    }

    fun get(key: String): String? = preferences?.getString(key, null)

    fun remove(key: String) {
        preferences?.edit { remove(key) }
    }

    /** Used by "delete all my data". */
    fun clear() {
        preferences?.edit { clear() }
    }

    companion object {
        private const val TAG = "AniSecureStore"
        private const val FILE_NAME = "ani_secure"

        /** Opaque per-install id the backend uses for rate limiting. Not a user identifier. */
        const val KEY_INSTALL_ID = "install_id"

        /** Spotify OAuth token, only present if the user connected Spotify. */
        const val KEY_SPOTIFY_TOKEN = "spotify_token"
        const val KEY_SPOTIFY_REFRESH = "spotify_refresh"
        const val KEY_SPOTIFY_EXPIRY = "spotify_expiry"
    }
}
