package com.fileapex.platform

/**
 * Platform abstraction for posting system notifications when a new Note or Message is received.
 */
expect fun notifyNoteReceived(sourceDeviceName: String, content: String)
