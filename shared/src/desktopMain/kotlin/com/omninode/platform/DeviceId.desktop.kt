package com.omninode.platform

import java.util.UUID

actual fun generateDeviceId(): String = UUID.randomUUID().toString()
