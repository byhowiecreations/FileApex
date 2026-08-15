package com.fileapex.cloud.drive

internal expect fun driveLog(message: String)

internal expect fun driveLogError(message: String, error: Throwable? = null)
