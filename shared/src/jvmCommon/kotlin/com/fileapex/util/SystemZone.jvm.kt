package com.fileapex.util

actual fun systemZoneId(): String = java.time.ZoneId.systemDefault().id
