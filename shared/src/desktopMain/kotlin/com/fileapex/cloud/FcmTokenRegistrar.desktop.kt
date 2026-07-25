package com.fileapex.cloud

/** Desktop has no FCM client — wake dispatch only (Mac → Android). */
actual object FcmTokenRegistrar {
    actual fun start() = Unit
    actual fun stop() = Unit
}
