package com.fileapex.domain.device

import com.fileapex.network.RenameOutcome

/** A device owns its name: a rename only changes the label held for it once the device itself took the name. */
object PeerRenamePolicy {
    /** The label to store for the renamed device, or null when it never received the rename and nothing may change. */
    fun labelToStore(outcome: RenameOutcome, typed: String): String? = when (outcome) {
        is RenameOutcome.Confirmed -> outcome.name
        RenameOutcome.Unconfirmed -> typed
        RenameOutcome.NotDelivered -> null
    }
}
