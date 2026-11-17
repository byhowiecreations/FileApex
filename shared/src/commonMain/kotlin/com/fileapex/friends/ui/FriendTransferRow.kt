package com.fileapex.friends.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.friends.FriendsHub
import com.fileapex.friends.transfer.FriendTransfer
import com.fileapex.ui.transfer.DefaultTranslate
import com.fileapex.ui.transfer.TransferRow
import com.fileapex.ui.transfer.TransferRowDensity
import com.fileapex.ui.transfer.TransferRowMappers

/** One file moving to or from a friend, drawn by the shared transfer row. */
@Composable
internal fun FriendTransferRow(transfer: FriendTransfer) {
    TransferRow(
        model = TransferRowMappers.fromFriend(transfer, DefaultTranslate),
        density = TransferRowDensity.Standard,
        onCancel = { FriendsHub.cancel(transfer.id) },
        onRetry = {},
        onRemove = {},
        modifier = Modifier.padding(top = 4.dp)
    )
}
