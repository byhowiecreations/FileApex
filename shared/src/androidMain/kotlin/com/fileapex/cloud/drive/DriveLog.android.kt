package com.fileapex.cloud.drive

import android.util.Log

private const val TAG = "DriveRelay"

internal actual fun driveLog(message: String) {
    Log.i(TAG, message)
}

internal actual fun driveLogError(message: String, error: Throwable?) {
    if (error != null) {
        Log.e(TAG, message, error)
    } else {
        Log.e(TAG, message)
    }
}
