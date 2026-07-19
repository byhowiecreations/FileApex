package com.omninode.cloud

/**
 * Desktop Firebase / OAuth constants.
 * Keep [WEB_CLIENT_ID] in sync with `omninode.google.web.client.id` in gradle.properties
 * (Android reads BuildConfig from that property).
 */
internal object DesktopCloudIds {
    const val WEB_CLIENT_ID =
        "REDACTED_WEB_CLIENT_ID"
    const val API_KEY = "REDACTED_FIREBASE_API_KEY"
    const val PROJECT_ID = "omninode-502915"
}
