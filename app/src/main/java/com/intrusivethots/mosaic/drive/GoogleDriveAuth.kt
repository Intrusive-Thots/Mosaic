package com.intrusivethots.mosaic.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.intrusivethots.mosaic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Credential Manager can sign a user in, but it does not grant Drive scopes.
 * [Identity.getAuthorizationClient] is the Identity API that returns a Drive access token.
 * Offline access is not requested, so Mosaic never handles a refresh token or a client secret.
 * The Android OAuth client is selected by package name and certificate; the client id is only
 * a local switch so an unconfigured build explains what to create in Cloud Console.
 */
internal class GoogleDriveAuth(private val context: Context) : DriveTokens {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val memory = HashMap<String, String>()
    private val gate = Mutex()

    fun configured(): Boolean = BuildConfig.GOOGLE_DRIVE_CLIENT_ID.isNotBlank()

    fun saved(): DriveAccount? {
        val email = prefs.getString(EMAIL, null) ?: return null
        return DriveAccount(email, prefs.getString(NAME, "").orEmpty())
    }

    fun save(account: DriveAccount) {
        prefs.edit().putString(EMAIL, account.email).putString(NAME, account.displayName).apply()
    }

    override suspend fun token(scope: String): String {
        val value = connect(listOf(scope), interactive = false).token
        if (value.isNullOrBlank()) {
            throw DriveFailure(DriveProblem.UNAUTHORIZED, "Sign in to Google Drive again.")
        }
        return value
    }

    override suspend fun invalidate(scope: String) {
        val dropped = gate.withLock {
            val current = memory.remove(scope)
            memory.filterValues { it == current }.keys.toList().forEach { memory.remove(it) }
            current
        }
        if (!dropped.isNullOrBlank()) ignoreCancel { clear(dropped) }
    }

    suspend fun connect(scopes: List<String>, interactive: Boolean): AuthOutcome {
        if (!configured()) return AuthOutcome(message = MISSING_CLIENT)
        if (!services()) return AuthOutcome(message = PLAY_MISSING)
        val cached = gate.withLock { cachedToken(scopes) }
        if (cached != null) return AuthOutcome(token = cached)
        return gate.withLock { request(scopes, interactive) }
    }

    suspend fun complete(intent: Intent): AuthOutcome {
        if (!configured()) return AuthOutcome(message = MISSING_CLIENT)
        return gate.withLock {
            val result = readResult(intent) ?: return@withLock AuthOutcome(message = "Google Drive did not finish connecting. Try again.")
            val value = store(result, listOf(DRIVE_READ_SCOPE, DRIVE_FILE_SCOPE))
            if (value == null) AuthOutcome(message = "Google Drive did not return access.") else AuthOutcome(token = value)
        }
    }

    suspend fun disconnect() {
        if (configured() && services()) {
            ignoreCancel { connect(listOf(DRIVE_READ_SCOPE), interactive = false) }
            ignoreCancel { connect(listOf(DRIVE_FILE_SCOPE), interactive = false) }
        }
        val held = gate.withLock {
            val values = memory.values.distinct()
            memory.clear()
            prefs.edit().clear().apply()
            values
        }
        held.forEach { value ->
            ignoreCancel { revoke(value) }
            ignoreCancel { clear(value) }
        }
    }

    private fun cachedToken(scopes: List<String>): String? {
        if (scopes.any { memory[it].isNullOrBlank() }) return null
        return memory[scopes.first()]
    }

    private suspend fun request(scopes: List<String>, interactive: Boolean): AuthOutcome {
        val authorization = AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()
        val result = try {
            withContext(Dispatchers.Main) {
                Identity.getAuthorizationClient(context).authorize(authorization).await()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return AuthOutcome(message = "Google Drive sign-in failed. Files and folders still work.")
        }
        if (result.hasResolution()) {
            if (!interactive) return AuthOutcome()
            val pending = result.pendingIntent
                ?: return AuthOutcome(message = "Google Drive did not open the sign-in screen.")
            return AuthOutcome(consent = pending)
        }
        val value = store(result, scopes) ?: return AuthOutcome(message = "Google Drive did not return access.")
        return AuthOutcome(token = value)
    }

    private fun readResult(intent: Intent): AuthorizationResult? {
        return try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }
    }

    private fun store(result: AuthorizationResult, requested: List<String>): String? {
        val value = result.accessToken?.takeIf { it.isNotBlank() } ?: return null
        requested.forEach { memory[it] = value }
        result.grantedScopes.orEmpty().forEach { scope -> memory[scope] = value }
        return value
    }

    private suspend fun clear(token: String) {
        val request = ClearTokenRequest.builder().setToken(token).build()
        withContext(Dispatchers.Main) {
            Identity.getAuthorizationClient(context).clearToken(request).await()
        }
    }

    private fun services(): Boolean {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return status == ConnectionResult.SUCCESS
    }

    private fun revoke(token: String) {
        val body = "token=${URLEncoder.encode(token, Charsets.UTF_8.name())}".toByteArray()
        val connection = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { output -> output.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PREFS = "drive_account"
        const val EMAIL = "email"
        const val NAME = "name"
        const val MISSING_CLIENT = "Add a Google OAuth client ID to use Drive folders inside Mosaic. Files and folders still work."
        const val PLAY_MISSING = "Google Play services is required to connect Google Drive. Files and folders still work."
    }
}

internal class AuthOutcome(
    val token: String? = null,
    val consent: PendingIntent? = null,
    val message: String? = null
)

private suspend fun ignoreCancel(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Silent refresh and revoke are best-effort. The local account is already cleared.
    }
}
