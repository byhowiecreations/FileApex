package com.fileapex.domain.device

import com.fileapex.network.RenameOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerRenamePolicyTest {
    @Test
    fun aConfirmedRenameStoresTheNameTheDeviceActuallyApplied() {
        assertEquals("Moto Signature", PeerRenamePolicy.labelToStore(RenameOutcome.Confirmed("Moto Signature"), "moto signature"))
    }

    @Test
    fun aDeviceFromBeforeConfirmationsHasTheTypedNameStoredOnceItAnswered() {
        assertEquals("moto signature", PeerRenamePolicy.labelToStore(RenameOutcome.Unconfirmed, "moto signature"))
    }

    @Test
    fun aRenameThatNeverReachedTheDeviceChangesNothingAnywhere() {
        assertNull(PeerRenamePolicy.labelToStore(RenameOutcome.NotDelivered, "moto signature"))
    }
}
