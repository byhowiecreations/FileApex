package com.omninode.cloud

/**
 * Desktop Firebase / OAuth constants.
 * Keep [WEB_CLIENT_ID] in sync with `omninode.google.web.client.id` in gradle.properties
 * (Android reads BuildConfig from that property).
 */
internal object DesktopCloudIds {
    const val WEB_CLIENT_ID =
        "603674284138-ptl6v5pas26f1imqaqi6rbeukkm7eqfb.apps.googleusercontent.com"
    const val API_KEY = "REDACTED_API_KEY"
    const val PROJECT_ID = "omninode-502915"
}
