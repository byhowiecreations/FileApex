package com.fileapex.cloud

actual fun fcmServiceAccountConfig(): FcmServiceAccountConfig? =
    GeneratedFcmCredentials.toConfig()
