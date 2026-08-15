package com.fileapex.cloud

/**
 * Minimum Google identity OAuth scopes.
 *
 * Firebase Auth needs an OpenID ID token. Email identifies the linked account in Settings.
 * Profile (name, photo, gender, languages, and other public personal info) is never requested.
 */
object GoogleIdentityScopes {
    const val OPEN_ID = "openid"
    const val EMAIL = "email"

    val identity: List<String> = listOf(OPEN_ID, EMAIL)

    fun identityQueryValue(): String = identity.joinToString(" ")
}
