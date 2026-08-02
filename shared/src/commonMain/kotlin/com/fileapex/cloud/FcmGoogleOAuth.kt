package com.fileapex.cloud

/** Google OAuth2 access token for FCM HTTP v1 (service-account JWT grant). */
internal expect object FcmGoogleOAuth {
    suspend fun accessToken(config: FcmServiceAccountConfig): String?
}
