package app.reelix.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import app.reelix.core.ReelixLog
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Outcome of initiating an authorization request with Google Identity [AuthorizationClient].
 */
sealed interface DriveAuthOutcome {
    /** The user already granted consent; access token is immediately available. */
    data class Success(val accessToken: String) : DriveAuthOutcome

    /** Explicit user consent or sign-in is required; launch the provided [IntentSenderRequest]. */
    data class ResolutionRequired(val intentSenderRequest: IntentSenderRequest) : DriveAuthOutcome

    /** User explicitly cancelled the authorization request. */
    data object Cancelled : DriveAuthOutcome

    /** An error occurred while communicating with Google Play Services. */
    data class Error(val message: String, val cause: Throwable? = null) : DriveAuthOutcome
}

/**
 * Result of parsing an Activity result intent returned from the Google consent resolution flow.
 */
sealed interface DriveTokenResult {
    /** Consent was granted and a valid access token was extracted. */
    data class Success(val accessToken: String) : DriveTokenResult

    /** The user cancelled or dismissed the consent prompt. */
    data object Cancelled : DriveTokenResult

    /** Parsing failed or authorization was denied. */
    data class Error(val message: String, val cause: Throwable? = null) : DriveTokenResult
}

/**
 * Native Google Identity Authorization manager for Google Drive API scopes.
 * Requests OAuth access tokens directly on Android without requiring an OAuth client secret.
 */
interface GoogleDriveAuthManager {
    suspend fun startAuthorization(context: Context): DriveAuthOutcome
    fun extractTokenFromResult(context: Context, resultCode: Int, data: Intent?): DriveTokenResult
    suspend fun silentRefresh(context: Context): String?
}

/**
 * Default implementation of [GoogleDriveAuthManager] using Google Play Services Identity AuthorizationClient.
 * Uses the standard drive.file scope: https://www.googleapis.com/auth/drive.file
 */
class DefaultGoogleDriveAuthManager(
    private val defaultContext: Context? = null,
    private val clientProvider: (Context) -> AuthorizationClient = { Identity.getAuthorizationClient(it) },
) : GoogleDriveAuthManager {

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    private fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
    }

    override suspend fun startAuthorization(context: Context): DriveAuthOutcome {
        val targetContext = defaultContext ?: context
        return try {
            val client = clientProvider(targetContext)
            val request = buildAuthorizationRequest()
            val result: AuthorizationResult = client.authorize(request).awaitResult()

            if (result.hasResolution()) {
                val pendingIntent = result.pendingIntent
                if (pendingIntent != null) {
                    val senderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    DriveAuthOutcome.ResolutionRequired(senderRequest)
                } else {
                    DriveAuthOutcome.Error("Authorization requires resolution, but PendingIntent is missing.")
                }
            } else {
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                    DriveAuthOutcome.Success(token)
                } else {
                    DriveAuthOutcome.Error("Authorization succeeded but returned an empty access token.")
                }
            }
        } catch (e: ApiException) {
            ReelixLog.w("Google Drive authorization ApiException: code ${e.statusCode}, message: ${e.message}", e)
            if (e.statusCode == CommonStatusCodes.CANCELED) {
                DriveAuthOutcome.Cancelled
            } else {
                DriveAuthOutcome.Error("Google Drive authorization failed: ${e.localizedMessage ?: "Status ${e.statusCode}"}", e)
            }
        } catch (e: Exception) {
            ReelixLog.w("Google Drive authorization exception", e)
            DriveAuthOutcome.Error("Google Drive authorization failed: ${e.localizedMessage ?: e.message}", e)
        }
    }

    override fun extractTokenFromResult(context: Context, resultCode: Int, data: Intent?): DriveTokenResult {
        if (resultCode == Activity.RESULT_CANCELED) {
            ReelixLog.d("Google Drive authorization flow cancelled by user (RESULT_CANCELED)")
            return DriveTokenResult.Cancelled
        }
        if (resultCode != Activity.RESULT_OK) {
            ReelixLog.w("Google Drive authorization returned non-OK result code: $resultCode")
            return DriveTokenResult.Error("Authorization was not completed (code $resultCode)")
        }
        if (data == null) {
            return DriveTokenResult.Error("No authorization result data received.")
        }

        val targetContext = defaultContext ?: context
        return try {
            val client = clientProvider(targetContext)
            val result = client.getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (!token.isNullOrBlank()) {
                DriveTokenResult.Success(token)
            } else {
                DriveTokenResult.Error("No access token found in authorization response.")
            }
        } catch (e: ApiException) {
            ReelixLog.w("ApiException parsing authorization result: code ${e.statusCode}, message: ${e.message}", e)
            if (e.statusCode == CommonStatusCodes.CANCELED) {
                DriveTokenResult.Cancelled
            } else {
                DriveTokenResult.Error("Failed to parse authorization response: ${e.localizedMessage ?: "Status ${e.statusCode}"}", e)
            }
        } catch (e: Exception) {
            ReelixLog.w("Exception parsing authorization result", e)
            DriveTokenResult.Error("Failed to parse authorization response: ${e.localizedMessage ?: e.message}", e)
        }
    }

    override suspend fun silentRefresh(context: Context): String? {
        val targetContext = defaultContext ?: context
        return try {
            val client = clientProvider(targetContext)
            val request = buildAuthorizationRequest()
            val result = client.authorize(request).awaitResult()
            if (!result.hasResolution() && !result.accessToken.isNullOrBlank()) {
                ReelixLog.d("Successfully refreshed Google Drive token silently via AuthorizationClient")
                result.accessToken
            } else {
                ReelixLog.d("Silent Drive token refresh requires resolution or returned empty token")
                null
            }
        } catch (e: Exception) {
            ReelixLog.w("Silent Drive token refresh failed: ${e.message}")
            null
        }
    }
}

/**
 * Awaits completion of a Google Play Services [Task] in a coroutine.
 */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
