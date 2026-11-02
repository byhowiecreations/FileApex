package com.fileapex.friends.ui

import com.fileapex.friends.PendingFriendOffer
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.friends.transfer.FriendTransferDirection

internal data class FriendQueueCounts(val files: Int, val offers: Int, val recent: Int) {
    val total: Int get() = files + offers
    val visible: Boolean get() = total > 0 || recent > 0
}

internal fun friendQueueCounts(transfers: List<FriendTransfer>, pending: List<PendingFriendOffer>): FriendQueueCounts {
    val offerIds = pending.map { it.id }.toSet()
    val files = transfers.count { !it.status.finished && !(it.direction == FriendTransferDirection.RECEIVE && it.id in offerIds) }
    return FriendQueueCounts(files, offerIds.size, transfers.count { it.status.finished })
}
