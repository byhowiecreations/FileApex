package com.fileapex.friends

/** Platform notifications for incoming offers and finished files, alongside the in-app queue. */
expect object FriendsNotifier {
    val canRequestPermission: Boolean
    fun requestPermission()
    fun offerPending(offer: PendingFriendOffer)
    fun offerResolved(offerId: String)
    /** A file finished. Android posts a notification with View and Open location when the app is in the background; the in-app card covers the foreground. */
    fun fileReceived(file: FriendReceivedFile)
    /** Called on every change of a transfer; the platform decides how often to redraw. */
    fun transferChanged(transfer: com.fileapex.friends.transfer.FriendTransfer)
}
