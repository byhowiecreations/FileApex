package com.fileapex.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RenameConfirmationTest {
    @Test
    fun aReplyWithTheAppliedNameIsConfirmed() {
        assertEquals(RenameOutcome.Confirmed("Desk"), RenameConfirmation.fromBody("""{"deviceName":" Desk "}"""))
    }

    @Test
    fun anEmptyBodyFromAnOlderBuildIsUnconfirmedNotAnError() {
        assertEquals(RenameOutcome.Unconfirmed, RenameConfirmation.fromBody(""))
        assertEquals(RenameOutcome.Unconfirmed, RenameConfirmation.fromBody("OK"))
        assertEquals(RenameOutcome.Unconfirmed, RenameConfirmation.fromBody("""{"deviceName":""}"""))
    }

    @Test
    fun unknownFieldsInAFutureReplyAreIgnored() {
        assertEquals(RenameOutcome.Confirmed("Desk"), RenameConfirmation.fromBody("""{"deviceName":"Desk","extra":1}"""))
    }
}
