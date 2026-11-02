package com.fileapex.friends

import com.fileapex.friends.transfer.FriendsWire
import com.fileapex.friends.transfer.FriendsReceiver
import org.junit.Assert.*
import org.junit.Test

class FriendsChecksumWireTest {
    private val id = "ab".repeat(16)
    private val hash = "cd".repeat(32)

    @Test fun wholeFileChecksumOffersRoundTripWithAndWithoutABatch() {
        for (batch in listOf("", "ef".repeat(16))) {
            val frame = "v4\n$id\n123\n1048576\n$batch\n$hash\nfile.bin"
            val offer = FriendsWire.decodeOffer(frame.encodeToByteArray())
            assertNotNull(offer)
            assertEquals(frame, FriendsWire.encodeOffer(offer!!).decodeToString())
        }
    }

    @Test fun checksumOffersRejectMissingOrMalformedDigests() {
        for (invalid in listOf("", "short", "g".repeat(64))) {
            assertNull(FriendsWire.decodeOffer("v4\n$id\n123\n1048576\n\n$invalid\nfile.bin".encodeToByteArray()))
        }
    }

    @Test fun aPeerCannotChooseAnInternalPartialFilenameAsAReceivedFilename() {
        val partial = FriendsReceiver.partName("friend", id)
        assertNotEquals(partial, FriendsWire.safeName(partial))
    }
}
