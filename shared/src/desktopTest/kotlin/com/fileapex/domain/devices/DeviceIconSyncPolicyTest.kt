package com.fileapex.domain.devices

import com.fileapex.data.settings.DeviceIconSyncSettings
import com.fileapex.notice.MapKvStore
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A paired device may tell this one which picture to show for a device; everything it sends is treated as hostile. */
class DeviceIconSyncPolicyTest {
    private fun request(png: String = Base64.getEncoder().encodeToString(ByteArray(2_000) { it.toByte() }), target: String = "fx-abc123") =
        DeviceIconSyncRequest(senderDeviceId = "fx-sender", senderPublicKey = "key", targetDeviceId = target, png = png)

    private fun check(request: DeviceIconSyncRequest, targetKnown: Boolean = true, last: Long? = null, now: Long = 10_000L) =
        DeviceIconSyncPolicy.check(request, targetKnown, last, now)

    @Test
    fun `a normal picture for a known device is accepted`() {
        val verdict = check(request())
        assertTrue(verdict is DeviceIconSyncPolicy.Verdict.Set)
        assertArrayEquals(ByteArray(2_000) { it.toByte() }, (verdict as DeviceIconSyncPolicy.Verdict.Set).png)
    }

    @Test
    fun `an empty picture clears the icon`() {
        assertEquals(DeviceIconSyncPolicy.Verdict.Clear, check(request(png = "")))
    }

    @Test
    fun `a picture that is not base64 is rejected`() {
        assertRejected("bad_image", check(request(png = "not base64 !!!")))
    }

    @Test
    fun `a picture over the size limit is rejected`() {
        val big = Base64.getEncoder().encodeToString(ByteArray(DeviceIconSyncPolicy.MAX_PNG_BYTES + 1))
        assertRejected("too_large", check(request(png = big)))
        val exact = Base64.getEncoder().encodeToString(ByteArray(DeviceIconSyncPolicy.MAX_PNG_BYTES))
        assertTrue(check(request(png = exact)) is DeviceIconSyncPolicy.Verdict.Set)
    }

    @Test
    fun `a target id that could be a path or is oddly shaped is rejected`() {
        for (target in listOf("", "../../x", "a/b", "a b", "a\\b", "x".repeat(129), "id\n", "id;drop")) {
            assertRejected("bad_target", check(request(target = target)))
        }
        for (target in listOf("fx-728c1683d5442871cb672c77e21a5dab", "6cb6d82c-0eee-4229-a2eb-b743cbfb7141", "legacy:123", "a.b_c-1")) {
            assertTrue(target, check(request(target = target)) is DeviceIconSyncPolicy.Verdict.Set)
        }
    }

    @Test
    fun `an unknown device is rejected`() {
        assertRejected("unknown_target", check(request(), targetKnown = false))
    }

    @Test
    fun `a sender that sends again within the interval is slowed down`() {
        assertRejected("rate_limited", check(request(), last = 9_800L, now = 10_000L))
        assertTrue(check(request(), last = 9_600L, now = 10_000L) is DeviceIconSyncPolicy.Verdict.Set)
    }

    @Test
    fun `only sensible picture sizes are accepted once decoded`() {
        assertTrue(DeviceIconSyncPolicy.dimensionsOk(256, 256))
        assertTrue(DeviceIconSyncPolicy.dimensionsOk(16, 512))
        assertFalse(DeviceIconSyncPolicy.dimensionsOk(15, 256))
        assertFalse(DeviceIconSyncPolicy.dimensionsOk(256, 513))
        assertFalse(DeviceIconSyncPolicy.dimensionsOk(0, 0))
    }

    @Test
    fun `receiver opt out setting defaults on and persists changes`() {
        val store = MapKvStore()
        val settings = DeviceIconSyncSettings(store)
        assertTrue(settings.acceptFromPeers.value)
        settings.setAcceptFromPeers(false)
        assertFalse(DeviceIconSyncSettings(store).acceptFromPeers.value)
        DeviceIconSyncSettings(store).setAcceptFromPeers(true)
        assertTrue(DeviceIconSyncSettings(store).acceptFromPeers.value)
    }

    private fun assertRejected(reason: String, verdict: DeviceIconSyncPolicy.Verdict) {
        assertEquals(DeviceIconSyncPolicy.Verdict.Rejected(reason), verdict)
    }
}
