package com.fileapex.friends

actual object FriendsNotifier {
    actual fun offerPending(offer: PendingFriendOffer) = Unit
    actual fun offerResolved(offerId: String) = Unit
    actual fun fileReceived(file: FriendReceivedFile) = Unit
    actual fun transferChanged(transfer: com.fileapex.friends.transfer.FriendTransfer) = Unit
}
