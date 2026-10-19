package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IrohAddressKeyTest {
    @Test
    fun theOrderOfDirectAddressesDoesNotChangeTheKey() {
        assertEquals(
            IrohAddressKey.of("id|https://relay.example.com|10.0.0.5:1,[::1]:2"),
            IrohAddressKey.of("id|https://relay.example.com|[::1]:2,10.0.0.5:1")
        )
    }

    @Test
    fun aNewDirectAddressOrRelayChangesTheKey() {
        val before = IrohAddressKey.of("id|https://a.example.com|10.0.0.5:1")
        assertNotEquals(before, IrohAddressKey.of("id|https://a.example.com|10.0.0.5:1,10.0.0.6:1"))
        assertNotEquals(before, IrohAddressKey.of("id|https://b.example.com|10.0.0.5:1"))
    }

    @Test
    fun aMalformedAddressIsComparedAsIs() {
        assertEquals("junk", IrohAddressKey.of("junk"))
    }

    @Test
    fun aDescribedChangeNamesWhatMovedWithoutShowingAddresses() {
        val before = "id|https://r.example.com|10.0.0.5:1000,[2600:1:2:3:aaaa:bbbb:cccc:dddd]:1000"
        val after = "id|https://r.example.com|10.0.0.5:1000,[2600:1:2:3:1111:2222:3333:4444]:1000"

        val text = IrohAddressKey.describeChange(before, after)

        assertEquals("relay same, direct +1 -1 (added v6-global; removed v6-global)", text)
        assertFalse(text.contains("2600"))
    }

    @Test
    fun aPortOnlyChangeAndARelayChangeAreNamed() {
        val before = "id|https://a.example.com|203.0.113.9:1000"
        val after = "id|https://b.example.com|203.0.113.9:2000"

        assertEquals("relay changed, direct +1 -1 (added v4-public; removed v4-public)", IrohAddressKey.describeChange(before, after))
        assertEquals("relay same, direct none", IrohAddressKey.describeChange(before, before))
    }

    @Test
    fun privateAndLinkLocalKindsAreTold() {
        val after = "id||192.168.1.5:1,100.70.1.2:1,[fe80::1]:1,[fd00::1]:1"

        assertEquals(
            "relay none, direct +4 -0 (added v4-private, v4-private, v6-link-local, v6-ula)",
            IrohAddressKey.describeChange("id||", after)
        )
    }
}
