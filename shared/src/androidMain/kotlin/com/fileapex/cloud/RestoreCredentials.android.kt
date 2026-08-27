package com.fileapex.cloud

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateRestoreCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetRestoreCredentialOption
import androidx.credentials.RestoreCredential
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.restorecredential.E2eeUnavailableException
import com.fileapex.data.settings.androidAppContextOrNull
import com.fileapex.di.FileApexServices
import java.security.SecureRandom

internal actual object RestoreCredentials {
    private const val TAG = "RestoreCredentials"
    private const val PREFS = "fileapex_restore_credentials"
    private const val KEY_CREATED = "restore_key_created"
    private const val KEY_CLOUD = "restore_key_cloud_backup"
    private const val KEY_PROBED = "restore_key_probed"
    private const val MIN_SDK = Build.VERSION_CODES.P

    actual fun alreadyProbedThisInstall(): Boolean {
        if (!supported()) return true
        return prefs()?.getBoolean(KEY_PROBED, false) == true
    }

    actual fun markProbedThisInstall() {
        prefs()?.edit()?.putBoolean(KEY_PROBED, true)?.apply()
    }

    actual suspend fun createForSignedInUser(uid: String, email: String) {
        if (!supported()) return
        if (uid.isBlank()) return
        val context = androidAppContextOrNull() ?: return
        val manager = CredentialManager.create(context)
        val existing = getRestoreCredential(manager, context)
        if (existing != null) {
            Log.i(TAG, "Restore key present")
            prefs()?.edit()?.putBoolean(KEY_CREATED, true)?.apply()
            return
        }
        val requestJson = RestoreCredentialJson.creationOptions(
            userId = uid,
            email = email,
            challengeB64 = randomChallenge()
        )
        val cloud = runCatching {
            createKey(manager, context, requestJson, cloudBackup = true)
            true
        }.recoverCatching { error ->
            if (error is E2eeUnavailableException) {
                Log.w(TAG, "Cloud backup E2EE unavailable - creating local restore key")
                createKey(manager, context, requestJson, cloudBackup = false)
                false
            } else {
                throw error
            }
        }
        cloud.onSuccess { cloudBacked ->
            val verified = getRestoreCredential(manager, context)
            prefs()?.edit()
                ?.putBoolean(KEY_CREATED, verified != null)
                ?.putBoolean(KEY_CLOUD, cloudBacked && verified != null)
                ?.apply()
            if (verified != null) {
                Log.i(
                    TAG,
                    if (cloudBacked) {
                        "Restore key created and verified (cloud-backed)"
                    } else {
                        "Restore key created and verified (local only)"
                    }
                )
            } else {
                Log.w(TAG, "Restore key create returned but getCredential found nothing")
            }
        }.onFailure { error ->
            Log.w(TAG, "Restore key create failed: ${error.message}")
        }
    }

    actual suspend fun clear() {
        val context = androidAppContextOrNull()
        if (supported() && context != null) {
            val manager = CredentialManager.create(context)
            runCatching {
                manager.clearCredentialState(
                    ClearCredentialStateRequest(ClearCredentialStateRequest.TYPE_CLEAR_RESTORE_CREDENTIAL)
                )
            }.onFailure { error ->
                Log.w(TAG, "Restore key clear failed: ${error.message}")
            }
            runCatching {
                manager.clearCredentialState(ClearCredentialStateRequest())
            }.onFailure { error ->
                Log.w(TAG, "Google credential clear failed: ${error.message}")
            }
        }
        // Keep probed so the next cold launch does not zero-tap re-link after an explicit unlink.
        prefs()?.edit()
            ?.clear()
            ?.putBoolean(KEY_PROBED, true)
            ?.apply()
    }

    actual suspend fun restoreGoogleIdToken(): Pair<String, String?>? {
        if (!supported()) return null
        val context = androidAppContextOrNull() ?: return null
        val manager = CredentialManager.create(context)
        val restore = getRestoreCredential(manager, context)
        if (restore != null) {
            Log.i(TAG, "Restore key retrieved")
        }
        val emailFromKey = restore?.let {
            RestoreCredentialJson.emailFromAssertion(it.authenticationResponseJson)
        }
        val emailHint = FileApexServices.settings.googleAccountEmail.value.ifBlank { null }
            ?: emailFromKey
        if (!GoogleLinkRestorePolicy.shouldAttemptSilentGoogleId(
                restoreKeyPresent = restore != null,
                backedUpEmail = emailHint.orEmpty()
            )
        ) {
            Log.i(TAG, "No restore key and no backed-up Google email - skipping silent Google")
            return null
        }
        val fromGoogleId = silentGoogleIdTokenFromCredentialManager(context)
        if (fromGoogleId != null) {
            Log.i(TAG, "Silent Google ID from Credential Manager")
            return fromGoogleId
        }
        val tokens = silentGoogleIdToken(context, emailHint)
        if (tokens != null) {
            Log.i(TAG, "Silent Google ID from Identity")
            return tokens
        }
        Log.w(TAG, "Restore key or backed-up email present but Google ID token unavailable")
        return null
    }

    private suspend fun getRestoreCredential(
        manager: CredentialManager,
        context: Context
    ): RestoreCredential? {
        return runCatching {
            val option = GetRestoreCredentialOption(RestoreCredentialJson.requestOptions(randomChallenge()))
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            manager.getCredential(context, request).credential as? RestoreCredential
        }.getOrElse { error ->
            if (error is NoCredentialException) {
                Log.i(TAG, "No restore key on this device")
            } else {
                Log.w(TAG, "Restore key get failed: ${error.message}")
            }
            null
        }
    }

    private suspend fun createKey(
        manager: CredentialManager,
        context: Context,
        requestJson: String,
        cloudBackup: Boolean
    ) {
        manager.createCredential(
            context,
            CreateRestoreCredentialRequest(requestJson, cloudBackup)
        )
    }

    private fun supported(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    private fun prefs() = androidAppContextOrNull()
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomChallenge(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return RestoreCredentialJson.base64Url(bytes)
    }
}
