package com.fileapex.cloud

/** Parsed Firebase service account fields for FCM HTTP v1 (generated at build time). */
data class FcmServiceAccountConfig(
    val projectId: String,
    val clientEmail: String,
    val privateKeyPem: String
) {
    val isUsable: Boolean =
        projectId.isNotBlank() && clientEmail.isNotBlank() && privateKeyPem.isNotBlank()
}

expect fun fcmServiceAccountConfig(): FcmServiceAccountConfig?
