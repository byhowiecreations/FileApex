package com.fileapex.cloud

/** Platform hook — registers the device FCM token with Firestore when cloud-linked. */
expect object FcmTokenRegistrar {
    fun start()
    fun stop()
}
