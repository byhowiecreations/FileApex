package com.fileapex.friends

/** Tells the user outside the app that a friend is waiting for an Accept. Android posts a notification, desktop shows the in-app prompt only. */
expect object FriendsNotifier {
    fun offerPending(offer: PendingFriendOffer)
    fun offerResolved(offerId: String)
    /** A file finished. Android posts a notification with View and Open location when the app is in the background; the in-app card covers the foreground. */
    fun fileReceived(file: FriendReceivedFile)
    /** Called on every change of a transfer; the platform decides how often to redraw. */
    fun transferChanged(transfer: com.fileapex.friends.transfer.FriendTransfer)
}
