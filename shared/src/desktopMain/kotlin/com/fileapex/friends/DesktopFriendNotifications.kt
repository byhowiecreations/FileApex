package com.fileapex.friends

import com.fileapex.i18n.AppI18n

internal interface DesktopFriendNotificationSink {
    fun post(id: String, title: String, body: String, onOpen: () -> Unit)
    fun remove(id: String)
}

internal class DesktopFriendNotifications(
    private val sink: DesktopFriendNotificationSink,
    private val foreground: () -> Boolean,
    private val completionEnabled: () -> Boolean,
    private val offerLive: (String) -> Boolean,
    private val openQueue: () -> Unit,
    private val openFile: (String) -> Unit
) {
    fun offerPending(offer: PendingFriendOffer) {
        if (foreground()) return
        sink.post("offer:${offer.id}", AppI18n.t("friends_offer_title", offer.friendLabel), offer.fileName) {
            if (offerLive(offer.id)) openQueue()
        }
    }

    fun offerResolved(id: String) = sink.remove("offer:$id")

    fun fileReceived(file: FriendReceivedFile) {
        if (foreground() || !completionEnabled()) return
        sink.post("received:${file.path}", AppI18n.t("friends_received_title", file.friendLabel), file.fileName) {
            openFile(file.path)
        }
    }
}
