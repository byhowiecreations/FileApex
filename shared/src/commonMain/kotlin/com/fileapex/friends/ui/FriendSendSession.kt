package com.fileapex.friends.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A friend the file screen is picking this device's files for. */
data class FriendSendTarget(val key: String, val name: String)

/**
 * A friend's Send asks the app to show this device's files; the first file screen for this device that appears
 * claims the request (and clears it), so only that screen turns into a picker and the next Local Files visit is normal.
 */
object FriendSendSession {
    private val request = MutableStateFlow<FriendSendTarget?>(null)
    val pending: StateFlow<FriendSendTarget?> = request.asStateFlow()

    fun begin(target: FriendSendTarget) {
        request.value = target
    }

    fun clear() {
        request.value = null
    }
}
