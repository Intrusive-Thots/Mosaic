package com.intrusivethots.mosaic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.intrusivethots.mosaic.engine.security.migrateApiKey

/**
 * User-supplied API keys only. Mosaic does not embed a developer key and does not transmit this value.
 * On-device ML Kit performs subject segmentation. The key is encrypted with an Android Keystore master key.
 * If the keystore cannot be opened, the key is not written to plain preferences.
 */
class KeystoreApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    val available: Boolean
    private val securePrefs: SharedPreferences?

    init {
        val opened = openEncryptedPrefs()
        securePrefs = opened
        available = opened != null
        if (opened != null) migrateLegacy(opened)
    }

    fun getApiKey(): String {
        val prefs = securePrefs ?: return ""
        return prefs.getString(KEY, "").orEmpty()
    }

    fun setApiKey(key: String): Boolean {
        val prefs = securePrefs ?: return false
        prefs.edit().putString(KEY, key.trim()).commit()
        legacyPrefs.edit().remove(LEGACY_KEY).apply()
        return true
    }

    private fun migrateLegacy(prefs: SharedPreferences) {
        val decision = migrateApiKey(
            legacyPlaintext = legacyPrefs.getString(LEGACY_KEY, null),
            encrypted = prefs.getString(KEY, "").orEmpty()
        )
        if (decision.writeEncrypted) {
            prefs.edit().putString(KEY, decision.key).commit()
        }
        if (decision.clearLegacyPlaintext) {
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    private fun openEncryptedPrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val SECURE_PREFS = "mosaic_secure_prefs"
        private const val LEGACY_PREFS = "mosaic_settings"
        private const val LEGACY_KEY = "gemini_api_key"
        private const val KEY = "user_api_key"
    }
}
