package com.fileapex.cloud

internal object GoogleLinkRestorePolicy {
    fun shouldProbeRestoreKey(linkedFlag: Boolean, alreadyProbedThisInstall: Boolean): Boolean =
        linkedFlag && !alreadyProbedThisInstall

    fun shouldClearLinkedFlag(
        linkedFlag: Boolean,
        hasFirebaseSession: Boolean,
        restoredIdToken: Boolean
    ): Boolean = linkedFlag && !hasFirebaseSession && !restoredIdToken

    fun shouldAttemptSilentGoogleId(
        linkedFlag: Boolean,
        configuredEmail: String
    ): Boolean = linkedFlag && configuredEmail.isNotBlank()
}
