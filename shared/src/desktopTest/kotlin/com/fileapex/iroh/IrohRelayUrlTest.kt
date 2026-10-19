package com.fileapex.iroh

import org.junit.Test
import org.junit.Assert.assertEquals

class IrohRelayUrlTest {
    private fun problem(input: String) = (IrohRelayUrl.check(input) as? IrohRelayUrlCheck.Invalid)?.problem

    @Test
    fun acceptsHostNamesAndNormalises() {
        assertEquals(IrohRelayUrlCheck.Valid("https://relay.example.com"), IrohRelayUrl.check("  https://Relay.Example.com/ "))
        assertEquals(IrohRelayUrlCheck.Valid("https://me.duckdns.org:8443"), IrohRelayUrl.check("https://me.duckdns.org:8443"))
    }

    @Test
    fun refusesIpAddressesAndLocalNames() {
        assertEquals(IrohRelayUrlProblem.IpAddress, problem("https://203.0.113.7"))
        assertEquals(IrohRelayUrlProblem.IpAddress, problem("https://203.0.113.7:443"))
        assertEquals(IrohRelayUrlProblem.IpAddress, problem("https://[2001:db8::1]"))
        assertEquals(IrohRelayUrlProblem.NoHostName, problem("https://localhost"))
        assertEquals(IrohRelayUrlProblem.NoHostName, problem("https://relay"))
    }

    @Test
    fun refusesOtherShapes() {
        assertEquals(IrohRelayUrlProblem.Empty, problem("  "))
        assertEquals(IrohRelayUrlProblem.NotHttps, problem("http://relay.example.com"))
        assertEquals(IrohRelayUrlProblem.NotHttps, problem("relay.example.com"))
        assertEquals(IrohRelayUrlProblem.Malformed, problem("https://relay.example.com/path"))
        assertEquals(IrohRelayUrlProblem.Malformed, problem("https://user@relay.example.com"))
        assertEquals(IrohRelayUrlProblem.Malformed, problem("https://relay.exa mple.com"))
        assertEquals(IrohRelayUrlProblem.Malformed, problem("https://relay.example.com:99999"))
    }
}
