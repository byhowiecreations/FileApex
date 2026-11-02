package com.fileapex.friends.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fileapex.friends.FriendInviteLink

internal class FriendInviteForm(prefill: String = "") {
    var link by mutableStateOf(prefill)
    var pin by mutableStateOf("")
        private set
    var name by mutableStateOf("")
    val inviteValid: Boolean get() = FriendInviteLink.parse(link) != null
    val canJoin: Boolean get() = inviteValid && pin.length == 6 && name.isNotBlank()

    fun pinChanged(value: String) { pin = value.filter { it in '0'..'9' }.take(6) }

    fun acceptScan(text: String): Boolean {
        val invite = FriendInviteLink.parse(text) ?: return false
        link = invite.toUri()
        pin = ""
        return true
    }
}
