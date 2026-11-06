package com.fileapex.drop

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual probe, skipped unless FILEAPEX_DROP_PROBE names a folder. It shares a generated file over the real relay, writes the
 * link into that folder and stays up until the link is spent or the limit passes, so a browser can be pointed at it.
 */
class GuestDropBrowserProbe {
    @Test fun serveOneFileForABrowser() = runBlocking {
        val dir = System.getenv("FILEAPEX_DROP_PROBE")
        assumeTrue(dir != null)
        val folder = File(dir!!).apply { mkdirs() }
        val megabytes = (System.getenv("FILEAPEX_DROP_PROBE_MB") ?: "40").toInt()
        val file = File(folder, "probe-source.bin")
        file.outputStream().buffered().use { out -> repeat(megabytes) { i -> out.write(ByteArray(1024 * 1024) { ((it + i) % 251).toByte() }) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val drops = GuestDrops(maxLive = 1)
        val service = GuestDropService({ embeddedGuestDropEngine() }, scope, drops)
        try {
            val result = service.share(file, 15 * 60_000L) as ShareResult.Ready
            File(folder, "link.txt").writeText(result.url)
            File(folder, "sha.txt").writeText(java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            val until = System.currentTimeMillis() + 14 * 60_000L
            while (System.currentTimeMillis() < until && drops.list().isNotEmpty()) delay(500)
            while (System.currentTimeMillis() < until && service.endpointIsUp()) delay(500)
            File(folder, "ended.txt").writeText(if (drops.list().isEmpty()) "spent or closed" else "timeout")
        } finally {
            service.revokeAll()
            scope.cancel()
        }
    }
}
