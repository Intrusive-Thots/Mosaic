package com.intrusivethots.mosaic.engine.security

data class ApiKeyMigrationResult(
    val key: String,
    val clearLegacyPlaintext: Boolean,
    val writeEncrypted: Boolean
)

/**
 * Moves a user-supplied key out of plaintext preferences.
 * The key is owned by the user. This app does not ship a developer key and does not upload the value.
 */
fun migrateApiKey(legacyPlaintext: String?, encrypted: String): ApiKeyMigrationResult {
    val secure = encrypted.trim()
    val legacy = legacyPlaintext?.trim().orEmpty()
    if (secure.isNotEmpty()) {
        return ApiKeyMigrationResult(
            key = secure,
            clearLegacyPlaintext = legacy.isNotEmpty(),
            writeEncrypted = false
        )
    }
    if (legacy.isNotEmpty()) {
        return ApiKeyMigrationResult(
            key = legacy,
            clearLegacyPlaintext = true,
            writeEncrypted = true
        )
    }
    return ApiKeyMigrationResult(key = "", clearLegacyPlaintext = false, writeEncrypted = false)
}
