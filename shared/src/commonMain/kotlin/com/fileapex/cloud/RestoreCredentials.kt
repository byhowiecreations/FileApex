package com.fileapex.cloud

internal expect object RestoreCredentials {
    fun alreadyProbedThisInstall(): Boolean

    fun markProbedThisInstall()

    suspend fun createForSignedInUser(uid: String, email: String)

    suspend fun clear()

    suspend fun restoreGoogleIdToken(): Pair<String, String?>?
}
