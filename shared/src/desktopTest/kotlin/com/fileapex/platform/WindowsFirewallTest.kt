package com.fileapex.platform

import com.fileapex.security.CommandRunner
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsFirewallTest {
    private val exe = "C:\\Users\\Pat O'Neil\\AppData\\Local\\Programs\\FileApex\\FileApex.exe"

    private fun decoded(args: List<String>): String {
        val encoded = args.joinToString(" ").substringAfter("-EncodedCommand").trim().trim('\'', ',', ' ').substringBefore("'")
        return String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)
    }

    private class Recorder(val exit: Int, val out: String) : CommandRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>, stdin: String?): Pair<Int, String> {
            calls += args
            return exit to out
        }
    }

    private fun firewall(runner: CommandRunner, windows: Boolean = true, path: String? = exe) =
        WindowsFirewall(runner, { path }, { windows })

    @Test
    fun anAllowRuleForThisExeMeansInboundIsOpen() {
        val out = "FIREWALL\t3\nRULE\tAllow\tTrue\tInbound\tPrivate, Public\n"
        assertEquals(FirewallInboundStatus.Allowed, WindowsFirewall.decide(out))
    }

    @Test
    fun aBlockRuleWinsOverAnAllowRule() {
        val out = "FIREWALL\t3\nRULE\tAllow\tTrue\tInbound\tPrivate\nRULE\tBlock\tTrue\tInbound\tPublic\n"
        assertEquals(FirewallInboundStatus.Blocked, WindowsFirewall.decide(out))
    }

    @Test
    fun disabledRulesAndOutboundRulesDoNotCount() {
        val out = "FIREWALL\t3\nRULE\tAllow\tFalse\tInbound\tAny\nRULE\tAllow\tTrue\tOutbound\tAny\nRULE\tBlock\tFalse\tInbound\tAny\n"
        assertEquals(FirewallInboundStatus.NoRule, WindowsFirewall.decide(out))
    }

    @Test
    fun noRuleAtAllIsReportedWhenTheFirewallIsOn() {
        assertEquals(FirewallInboundStatus.NoRule, WindowsFirewall.decide("FIREWALL\t2\n"))
    }

    @Test
    fun aFirewallThatIsOffBlocksNothing() {
        assertEquals(FirewallInboundStatus.Allowed, WindowsFirewall.decide("FIREWALL\t0\nRULE\tBlock\tTrue\tInbound\tAny\n"))
    }

    @Test
    fun outputWeCannotReadIsUnknownSoNoCardIsShown() {
        assertEquals(FirewallInboundStatus.Unknown, WindowsFirewall.decide(""))
        assertEquals(FirewallInboundStatus.Unknown, WindowsFirewall.decide("garbage\n"))
    }

    @Test
    fun statusRunsPowerShellWithTheExeAndReadsItsAnswer() {
        val runner = Recorder(0, "FIREWALL\t3\nRULE\tBlock\tTrue\tInbound\tPrivate, Public\n")
        assertEquals(FirewallInboundStatus.Blocked, firewall(runner).status())
        val call = runner.calls.single()
        assertEquals("powershell", call.first())
        assertTrue(call.contains("-NoProfile"))
        val script = decoded(call)
        assertTrue(script.contains("Get-NetFirewallApplicationFilter"))
        assertTrue("quote not doubled: $script", script.contains("Pat O''Neil"))
    }

    @Test
    fun aFailedOrUnreadableCheckIsUnknownNotBlocked() {
        assertEquals(FirewallInboundStatus.Unknown, firewall(Recorder(1, "FIREWALL\t3\n")).status())
        val throwing = CommandRunner { _, _ -> throw java.io.IOException("powershell missing") }
        assertEquals(FirewallInboundStatus.Unknown, firewall(throwing).status())
    }

    @Test
    fun otherSystemsAndUnknownExePathsNeverStartAProcess() {
        val runner = Recorder(0, "FIREWALL\t3\n")
        assertEquals(FirewallInboundStatus.NotApplicable, firewall(runner, windows = false).status())
        assertEquals(FirewallInboundStatus.NotApplicable, firewall(runner, path = null).status())
        assertFalse(firewall(runner, windows = false).allow())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun allowAsksForElevationAndAddsAnInboundAllowRuleAfterRemovingBlocks() {
        val runner = Recorder(0, "")
        assertTrue(firewall(runner).allow())
        val call = runner.calls.single()
        val outer = call.joinToString(" ")
        assertTrue(outer.contains("-Verb RunAs"))
        val script = decoded(call)
        assertTrue(script.contains("Remove-NetFirewallRule"))
        assertTrue(script.contains("'Block'"))
        assertTrue(script.contains("New-NetFirewallRule"))
        assertTrue(script.contains("-Direction Inbound"))
        assertTrue(script.contains("-Action Allow"))
        assertTrue(script.contains("-Profile Private,Public"))
        assertTrue(script.contains("Pat O''Neil"))
        assertTrue(script.indexOf("Remove-NetFirewallRule") < script.indexOf("New-NetFirewallRule"))
    }

    @Test
    fun aDeclinedElevationOrFailedScriptReportsFalse() {
        assertFalse(firewall(Recorder(1, "")).allow())
        assertFalse(firewall(CommandRunner { _, _ -> throw java.io.IOException("x") }).allow())
    }
}
