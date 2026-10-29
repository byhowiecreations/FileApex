package com.fileapex.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {
    @Test
    fun anAddressKeepsOnlyItsLastOctetAndLosesItsPort() {
        assertEquals("connect to .130 failed", LogRedactor.redact("connect to 192.168.1.130:8080 failed"))
        assertEquals("from .5 to .7", LogRedactor.redact("from 10.0.0.5 to 172.16.16.7"))
    }

    @Test
    fun theUrlInAKtorTimeoutLosesItsAddress() {
        val text = "Request timeout has expired [url=http://10.255.255.1:8080/api/v1/health, request_timeout=300 ms]"
        val out = LogRedactor.redact(text)
        assertFalse(out, out.contains("10.255.255"))
        assertEquals("Request timeout has expired [url=http://.1/api/v1/health, request_timeout=300 ms]", out)
    }

    @Test
    fun anAndroidConnectErrorNamesNeitherAddress() {
        val text = "failed to connect to /192.168.1.5 (port 8080) from /192.168.1.99 (port 41234) after 10000ms: isConnected failed: ECONNREFUSED"
        val out = LogRedactor.redact(text)
        assertFalse(out, Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(out))
        assertEquals(true, out.contains(".5") && out.contains(".99") && out.contains("ECONNREFUSED"))
    }

    @Test
    fun anIpv6AddressIsHidden() {
        assertEquals("peer [ipv6] gone", LogRedactor.redact("peer fe80::1ff:fe23:4567:890a gone"))
    }

    @Test
    fun userFilePathsAreReplacedOnEveryPlatform() {
        assertEquals("[path] (No such file or directory)", LogRedactor.redact("/Users/someone/Documents/plans.txt (No such file or directory)"))
        assertEquals("open failed: [path]: ENOENT", LogRedactor.redact("open failed: /storage/emulated/0/Download/report.pdf: ENOENT"))
        assertEquals("[path] missing", LogRedactor.redact("""C:\Users\cliff\Documents\x.txt missing"""))
        assertEquals("[path]", LogRedactor.redact("/home/cliff/.config/fileapex/state.json"))
        assertEquals("[path]", LogRedactor.redact("/Volumes/unraid/App Development/file.bin".substringBefore(" Development")))
        assertEquals("[path]", LogRedactor.redact("/sdcard/DCIM/a.jpg"))
    }

    @Test
    fun ordinaryLogTextIsLeftAlone() {
        val lines = listOf(
            "FCM received type=presence_wake keys=epochMs, sourceDeviceId, type",
            "Presence wake received from 77d4304a-ba16-4c8e-a86d-96f884b334a9",
            "FileApex v0.19.9a (GitHub) code 223",
            "NoticeSync: Updated",
            "tls-front-1 accepted on port",
            "java.lang.IllegalStateException: bug",
            "noteId=0b6d1d3e driveFileId=1AbC_dEf",
            "ratio 3.5 of 12.25 in 1.0.2"
        )
        lines.forEach { assertEquals(it, LogRedactor.redact(it)) }
    }

    @Test
    fun redactingTwiceChangesNothingMore() {
        val once = LogRedactor.redact("url=http://192.168.1.130:9/x and /Users/a/b.txt")
        assertEquals(once, LogRedactor.redact(once))
    }
}
