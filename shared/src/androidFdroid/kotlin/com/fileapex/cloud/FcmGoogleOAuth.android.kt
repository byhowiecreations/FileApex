package com.fileapex.cloud

internal actual object FcmGoogleOAuth {
    actual suspend fun accessToken(config: FcmServiceAccountConfig): String? = null
}
