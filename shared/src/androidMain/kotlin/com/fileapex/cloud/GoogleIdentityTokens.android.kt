package com.fileapex.cloud

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.tasks.await

internal fun googleIdTokenFromAuthorization(
    result: AuthorizationResult
): Pair<String, String?> {
    val account = result.toGoogleSignInAccount()
    val fromAccount = account?.idToken.orEmpty()
    if (fromAccount.isBlank()) {
        error("Google authorization returned no ID token")
    }
    val email = account?.email?.ifBlank { null } ?: emailFromGoogleIdToken(fromAccount)
    return fromAccount to email
}

internal suspend fun interactiveGoogleIdToken(activity: Activity): Pair<String, String?> {
    val clientId = googleWebClientId()
    require(clientId.isNotBlank()) {
        "Set fileapex.google.web.client.id in gradle.properties (Google Web OAuth client ID)"
    }
    val option = GetSignInWithGoogleOption.Builder(clientId).build()
    val response = CredentialManager.create(activity).getCredential(
        activity,
        GetCredentialRequest.Builder().addCredentialOption(option).build()
    )
    return googleIdTokenFromCredential(response.credential.data)
}

internal suspend fun silentGoogleIdTokenFromCredentialManager(
    context: Context
): Pair<String, String?>? {
    return googleIdAutoSelect(context, authorizedOnly = true)
        ?: googleIdAutoSelect(context, authorizedOnly = false)
}

private suspend fun googleIdAutoSelect(
    context: Context,
    authorizedOnly: Boolean
): Pair<String, String?>? {
    val clientId = googleWebClientId()
    if (clientId.isBlank()) return null
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(clientId)
        .setFilterByAuthorizedAccounts(authorizedOnly)
        .setAutoSelectEnabled(true)
        .build()
    val response = runCatching {
        CredentialManager.create(context).getCredential(
            context,
            GetCredentialRequest.Builder().addCredentialOption(option).build()
        )
    }.getOrElse { error ->
        println(
            "RestoreCredentials: Google ID auto-select " +
                "(authorizedOnly=$authorizedOnly) failed - ${error.message}"
        )
        return null
    }
    return runCatching {
        googleIdTokenFromCredential(response.credential.data)
    }.getOrElse { error ->
        println("RestoreCredentials: Google ID parse failed - ${error.message}")
        null
    }
}

private fun googleIdTokenFromCredential(data: Bundle): Pair<String, String?> {
    val google = GoogleIdTokenCredential.createFrom(data)
    val token = google.idToken
    require(token.isNotBlank()) { "Google sign-in returned no ID token" }
    val email = google.id.ifBlank { null } ?: emailFromGoogleIdToken(token)
    return token to email
}

internal suspend fun silentGoogleIdToken(
    context: Context,
    emailHint: String?
): Pair<String, String?>? {
    val clientId = googleWebClientId()
    if (clientId.isBlank()) return null
    val builder = AuthorizationRequest.builder()
        .setRequestedScopes(GoogleIdentityScopes.identity.map { Scope(it) })
        .setOptOutIncludingGrantedScopes(true)
        .requestOfflineAccess(clientId, false)
    val email = emailHint?.trim().orEmpty()
    if (email.isNotBlank()) {
        builder.setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
    }
    val result = Identity.getAuthorizationClient(context)
        .authorize(builder.build())
        .await()
    if (result.hasResolution()) {
        println("RestoreCredentials: silent Google auth needs UI - skipping")
        return null
    }
    return runCatching { googleIdTokenFromAuthorization(result) }.getOrNull()
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"
