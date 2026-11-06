package com.fileapex.drop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the phone's ongoing notice says while links are open. Off means no notice and no foreground service. */
sealed interface GuestDropNotice {
    data object Off : GuestDropNotice
    data class One(val name: String) : GuestDropNotice
    data class Many(val count: Int) : GuestDropNotice
}

object GuestDropNoticeStrings {
    val keys = listOf("channel_guest_link", "channel_guest_link_desc", "share_link_notice_title", "share_link_notice_one", "share_link_notice_many")
}

fun guestDropNoticeFor(active: List<DropInfo>): GuestDropNotice = when (active.size) {
    0 -> GuestDropNotice.Off
    1 -> GuestDropNotice.One(active[0].name)
    else -> GuestDropNotice.Many(active.size)
}

/** Calls [show] whenever the notice changes, never twice for the same one. */
class GuestDropNoticeWatcher(scope: CoroutineScope, active: StateFlow<List<DropInfo>>, private val show: (GuestDropNotice) -> Unit) {
    private var last: GuestDropNotice = GuestDropNotice.Off

    init {
        scope.launch {
            active.collect { links ->
                val now = guestDropNoticeFor(links)
                if (now != last) {
                    last = now
                    show(now)
                }
            }
        }
    }
}
