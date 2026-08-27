package com.fileapex.cloud

internal actual object RestoreCredentials {
    actual fun alreadyProbedThisInstall(): Boolean = true

    actual fun markProbedThisInstall() = Unit

    actual suspend fun createForSignedInUser(uid: String, email: String) = Unit

    actual suspend fun clear() = Unit

    actual suspend fun restoreGoogleIdToken(): Pair<String, String?>? = null
}
