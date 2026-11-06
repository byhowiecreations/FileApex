package com.fileapex.cli

import com.fileapex.cli.ipc.CliIpcClient
import com.fileapex.cli.ipc.CliIpcDaemon
import com.fileapex.cli.ipc.CliAppStatus
import com.fileapex.domain.launch.DesktopLaunchInbox
import com.fileapex.domain.launch.DesktopLaunchRequest
import com.fileapex.platform.DesktopInstanceWire
import com.fileapex.platform.DesktopSingleInstance
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CliIpcClientFailureTest {
    @get:Rule val temp = TemporaryFolder()

    private fun withApp(body: (key: ByteArray) -> Unit) {
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), DesktopLaunchInbox()::offer))
        try { body(DesktopInstanceWire.readDescriptor(temp.root).key()) } finally { owner.close() }
    }

    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** A listener standing in for the app: it authenticates with [serverKey], reads the request line, then closes. */
    private fun fakeApp(serverKey: ByteArray, body: (ServerSocket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val thread = Thread {
                runCatching {
                    server.accept().use { s ->
                        s.soTimeout = 3000
                        if (DesktopInstanceWire.serverAuthenticate(s, serverKey, "cli")) {
                            while (s.getInputStream().read().let { it != -1 && it != '\n'.code }) { }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            body(server)
            thread.join(5000)
        }
    }

    private fun <T> captureErr(block: () -> T): Pair<T, String> {
        val original = System.err
        val bytes = ByteArrayOutputStream()
        System.setErr(PrintStream(bytes, true))
        try { return block() to bytes.toString() } finally { System.setErr(original) }
    }

    @Test fun nothingListeningMeansTheAppIsNotRunning() = withApp { _ ->
        val port = closedPort()
        assertNull(CliIpcClient.forwardCommand(listOf("devices"), temp.root.path, port, temp.root))
        assertEquals(CliAppStatus.NotRunning, CliIpcClient.appStatus(port, temp.root))
    }

    @Test fun noHandoffFileMeansTheAppIsNotRunning() {
        assertNull(CliIpcClient.forwardCommand(listOf("devices"), temp.root.path, closedPort(), temp.root))
        assertEquals(CliAppStatus.NotRunning, CliIpcClient.appStatus(closedPort(), temp.root))
    }

    @Test fun aConnectionLostBeforeTheResultIsAFailureNotSuccess() = withApp { key ->
        fakeApp(key) { server ->
            val (code, err) = captureErr { CliIpcClient.forwardCommand(listOf("devices"), temp.root.path, server.localPort, temp.root) }
            assertEquals(1, code)
            assertTrue(err, err.contains("lost"))
        }
    }

    @Test fun aRefusedCredentialIsReportedAndNotCalledNotRunning() = withApp { _ ->
        fakeApp(ByteArray(32) { 7 }) { server ->
            val (code, err) = captureErr { CliIpcClient.forwardCommand(listOf("devices"), temp.root.path, server.localPort, temp.root) }
            assertEquals(1, code)
            assertTrue(err, err.contains("refused"))
        }
        fakeApp(ByteArray(32) { 7 }) { server ->
            assertEquals(CliAppStatus.Refused, CliIpcClient.appStatus(server.localPort, temp.root))
        }
    }

    @Test fun aRunningDaemonIsReportedAsRunning() = withApp { key ->
        val port = checkNotNull(CliIpcDaemon.start(0, key))
        try {
            assertEquals(CliAppStatus.Running, CliIpcClient.appStatus(port, temp.root))
        } finally { CliIpcDaemon.stop() }
    }
}
