package com.fileapex.cloud.drive

internal actual fun driveLog(message: String) {
    println("DriveRelay: $message")
}

internal actual fun driveLogError(message: String, error: Throwable?) {
    println("DriveRelay: $message${error?.message?.let { " - $it" }.orEmpty()}")
}
