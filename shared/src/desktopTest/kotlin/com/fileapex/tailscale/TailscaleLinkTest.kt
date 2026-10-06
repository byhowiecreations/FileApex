package com.fileapex.tailscale

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TailscaleLinkTest {

    @Test
    fun disabledAndMissingKeyNeverReportAnAddress() {
        val off = reconcileTailscale(enabled = false, authKey = "tskey-auth-abcdefghijklmnopqrst", engine = null)
        assertEquals(TailscalePhase.Off, off.phase)
        assertEquals("", off.tailnetIp)
        assertTrue(off.configurationSaved)

        val interactive = reconcileTailscale(enabled = true, authKey = "  ", engine = null)
        assertEquals(TailscalePhase.Starting, interactive.phase)
        assertFalse(interactive.configurationSaved)
        assertTrue(interactive.enabled)
        assertEquals("", interactive.tailnetIp)

        val starting = reconcileTailscale(
            enabled = true,
            authKey = "tskey-auth-abcdefghijklmnopqrst",
            engine = null
        )
        assertEquals(TailscalePhase.Starting, starting.phase)
    }

    @Test
    fun savedKeyOrSignedInNodeIsReadyWithoutOpeningLogin() {
        assertTrue(tailscaleReadyWithoutLogin("tskey-auth-abcdefghijklmnopqrst", null))
        assertFalse(tailscaleReadyWithoutLogin("", null))
        assertFalse(tailscaleReadyWithoutLogin("tskey-auth-short", "{}"))
        val signedIn = stateWithNodeKey(loggedOut = false, nodeKey = "privkey:" + "ab".repeat(32))
        assertTrue(tailscaleStateIsSignedIn(signedIn))
        assertTrue(tailscaleReadyWithoutLogin("", signedIn))
        assertFalse(tailscaleStateIsSignedIn(stateWithNodeKey(loggedOut = true, nodeKey = "privkey:" + "ab".repeat(32))))
        assertFalse(tailscaleStateIsSignedIn(stateWithNodeKey(loggedOut = false, nodeKey = "privkey:" + "0".repeat(64))))
        assertFalse(isStoredNodePrivateKey("privkey:abcd"))
        assertFalse(tailscaleStateIsSignedIn("{}"))
        val raw = """{"profile-1":{"LoggedOut":false,"Config":{"PrivateNodeKey":"${"privkey:" + "cd".repeat(32)}"}}}"""
        assertTrue(tailscaleStateIsSignedIn(raw))
    }

    @Test
    fun connectedStateKeepsTheEngineAddress() {
        val up = reconcileTailscale(
            enabled = true,
            authKey = "tskey-auth-abcdefghijklmnopqrst",
            engine = TailscaleLinkSnapshot(TailscalePhase.Up, tailnetIp = "100.64.1.2")
        )
        assertEquals(TailscalePhase.Up, up.phase)
        assertEquals("100.64.1.2", up.tailnetIp)
    }

    @Test
    fun bringUpRejectsBadKeysAndDoesNotInventAnAddress() {
        val bad = evaluateTsnetBringUp("not-a-key", nativeLinked = true, tailnetIp = "100.1.1.1")
        assertEquals(TailscalePhase.Failed, bad.phase)
        assertEquals("invalid_auth_key", bad.detail)
        assertEquals("", bad.tailnetIp)

        val unlinked = evaluateTsnetBringUp(
            "tskey-auth-abcdefghijklmnopqrst",
            nativeLinked = false,
            tailnetIp = "100.1.1.1"
        )
        assertEquals(TailscalePhase.Down, unlinked.phase)
        assertEquals("", unlinked.tailnetIp)
        val afterSave = tailscaleAfterBringUp("tskey-auth-abcdefghijklmnopqrst", unlinked)
        assertFalse(afterSave.enabled)
        assertEquals(TailscalePhase.Down, afterSave.phase)
        assertEquals("tsnet_not_linked", afterSave.detail)
        assertEquals("", afterSave.tailnetIp)
        assertTrue(afterSave.configurationSaved)
    }

    @Test
    fun sandboxDenialUnlinksAndClearsTheSwitch() {
        val detail = "tsnet.Up: tsnet: route ip+net: netlinkrib: permission denied"
        assertTrue(isTailscaleSandboxDenial(detail))
        assertFalse(isTailscaleSandboxDenial("invalid auth key"))
        val denied = tailscaleStartFailure(detail)
        assertEquals(TailscalePhase.Down, denied.phase)
        assertEquals("tsnet_not_linked", denied.detail)
        assertEquals("", denied.tailnetIp)
        val after = tailscaleAfterBringUp("tskey-auth-abcdefghijklmnopqrst", denied)
        assertFalse(after.enabled)
        assertEquals(TailscalePhase.Down, after.phase)
        assertEquals("tsnet_not_linked", after.detail)
    }

    @Test
    fun startFailureClearsTheSwitchAndKeepsTheDetail() {
        val failed = TailscaleLinkSnapshot(TailscalePhase.Failed, detail = "no tailnet address")
        val after = tailscaleAfterBringUp("tskey-auth-abcdefghijklmnopqrst", failed)
        assertFalse(after.enabled)
        assertEquals(TailscalePhase.Failed, after.phase)
        assertEquals("no tailnet address", after.detail)
    }

    @Test
    fun loginAndExpiryStayEnabled() {
        val key = "tskey-auth-abcdefghijklmnopqrst"
        val login = tailscaleAfterBringUp(key, snapshotFromBackend("NeedsLogin", keyExpired = false, tailnetIp = ""))
        assertTrue(login.enabled)
        assertEquals(TailscalePhase.NeedsLogin, login.phase)
        val expired = tailscaleAfterBringUp(
            key,
            snapshotFromBackend("NeedsLogin", keyExpired = true, tailnetIp = "100.64.1.2")
        )
        assertTrue(expired.enabled)
        assertEquals(TailscalePhase.KeyExpired, expired.phase)
        assertFalse(tailscaleEnrollmentReset("", key, force = false))
        val fingerprint = tailscaleAuthKeyFingerprint(key)
        assertFalse(tailscaleEnrollmentReset(fingerprint, "", force = false))
        assertFalse(tailscaleEnrollmentReset(fingerprint, key, force = false))
        assertTrue(tailscaleEnrollmentReset(fingerprint, "tskey-auth-zzzzzzzzzzzzzzzzzzzz", force = false))
        assertTrue(tailscaleEnrollmentReset(fingerprint, key, force = true))
    }

    @Test
    fun backendStatesMapWithoutStartingTheNode() {
        assertEquals(TailscalePhase.Up, snapshotFromBackend("Running", false, "100.64.1.2").phase)
        assertEquals(TailscalePhase.Starting, snapshotFromBackend("Running", false, "").phase)
        assertEquals(TailscalePhase.NeedsLogin, snapshotFromBackend("NeedsMachineAuth", false, "").phase)
        assertEquals("needs_login", snapshotFromBackend("NeedsLogin", false, "").detail)
        assertEquals(TailscalePhase.Starting, snapshotFromBackend("Starting", false, "").phase)
        assertEquals(TailscalePhase.Starting, snapshotFromBackend("NoState", false, "").phase)
        assertEquals(TailscalePhase.Down, snapshotFromBackend("Stopped", false, "100.64.1.2").phase)
        assertEquals("stopped", snapshotFromBackend("Stopped", false, "").detail)
        assertEquals(TailscalePhase.Failed, snapshotFromBackend("InUseOtherUser", false, "").phase)
        assertEquals("start_failed", snapshotFromBackend("  ", false, "").detail)
        val expired = snapshotFromBackend("Running", true, "100.64.1.2")
        assertEquals(TailscalePhase.KeyExpired, expired.phase)
        assertEquals("key_expired", expired.detail)
        assertEquals("100.64.1.2", expired.tailnetIp)
    }

    @Test
    fun nodeHostnameUsesTheDeviceId() {
        assertEquals(
            "fileapex-550e8400e29b41d4a716446655440000",
            tailscaleNodeHostname("550e8400-e29b-41d4-a716-446655440000")
        )
    }

    @Test
    fun consoleHostnameUsesTheDeviceName() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals("fileapex-macbook-pro", tailscaleConsoleHostname("MacBook Pro", id))
        assertEquals("fileapex-moto-signature", tailscaleConsoleHostname("Moto Signature", id))
        assertEquals(
            "fileapex-550e8400e29b41d4a716446655440000",
            tailscaleConsoleHostname("   ", id)
        )
    }

    @Test
    fun linkedNodeStaysEnabledWithTheReportedAddress() {
        val up = evaluateTsnetBringUp(
            "tskey-auth-abcdefghijklmnopqrst",
            nativeLinked = true,
            tailnetIp = "100.64.1.2"
        )
        val after = tailscaleAfterBringUp("tskey-auth-abcdefghijklmnopqrst", up)
        assertTrue(after.enabled)
        assertEquals(TailscalePhase.Up, after.phase)
        assertEquals("100.64.1.2", after.tailnetIp)
        assertEquals("", after.detail)
    }

    @Test
    fun onlyCgnatAddressesUseTheUserspaceDial() {
        assertTrue(isTailscaleIPv4("100.64.0.1"))
        assertTrue(isTailscaleIPv4("100.127.255.255"))
        assertFalse(isTailscaleIPv4("100.63.255.255"))
        assertFalse(isTailscaleIPv4("100.128.0.1"))
        assertFalse(isTailscaleIPv4("172.16.16.105"))
        assertFalse(isTailscaleIPv4("100.64.1"))
        assertEquals("fileapex-honor-x9d", tailscaleHostname("HONOR X9d"))
        assertEquals("fileapex-device", tailscaleHostname("   "))
    }

    @Test
    fun adminConsoleLinkOpensTheKeysPage() {
        assertEquals(
            "https://login.tailscale.com/admin/settings/keys",
            TAILSCALE_ADMIN_KEYS_URL
        )
        val page = File("src/commonMain/kotlin/com/fileapex/ui/SettingsTailscalePage.kt")
        val text = page.readText()
        assertTrue(text.contains("TAILSCALE_ADMIN_KEYS_URL"))
        assertFalse(text.contains("adminSettings/keys"))
        assertTrue(text.contains("tailscale_status_up_ip"))
        assertTrue(text.contains("tailscale_reauthenticate"))
        assertTrue(text.contains("tailscale_sign_in"))
        assertTrue(text.contains("tailscale_advanced_users"))
        assertTrue(text.contains("tailscale_signin_guide"))
        assertFalse(text.contains("Ephemeral"))
        assertTrue(text.contains("tailscale_status_already_connected"))
        assertTrue(text.contains("tailscale_status_needs_login"))
        assertTrue(text.contains("tailscale_status_key_expired"))
        val english = File("src/commonMain/composeResources/files/i18n/en.xml").readText()
        assertTrue(english.contains("tailscale_sign_in"))
        assertFalse(english.contains("Ephemeral"))
        assertFalse(english.contains("add a tag"))
        assertEquals(
            "https://login.tailscale.com/a/abc",
            tailscaleLoginUrl("https://login.tailscale.com/a/abc")
        )
        assertNull(tailscaleLoginUrl("http://login.tailscale.com/a/abc"))
        assertNull(tailscaleLoginUrl("https://example.com/a/abc"))
        assertFalse(text.contains("tailscale_ip_label"))
    }

    @Test
    fun tailscaleSourcesDoNotCallVpnService() {
        val root = File("src")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.path.contains("/tailscale/") && it.extension == "kt" }
            .filter { "Test" !in it.name }
            .filter { file ->
                val text = file.readText()
                text.contains("android.net.VpnService") || text.contains("VpnService(")
            }
            .map { it.path }
            .toList()
        assertTrue(hits.toString(), hits.isEmpty())
        assertFalse(evaluateTsnetBringUp("tskey-auth-abcdefghijklmnopqrst", false, "").tailnetIp.isNotEmpty())
    }

    private fun stateWithNodeKey(loggedOut: Boolean, nodeKey: String): String {
        val prefs = """{"LoggedOut":$loggedOut,"Config":{"PrivateNodeKey":"$nodeKey"}}"""
        val encoded = Base64.getEncoder().encodeToString(prefs.encodeToByteArray())
        return """{"profile-1":"$encoded"}"""
    }
}
