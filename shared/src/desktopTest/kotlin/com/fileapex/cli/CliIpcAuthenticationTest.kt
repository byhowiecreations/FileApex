package com.fileapex.cli

import com.fileapex.cli.ipc.CliIpcClient
import com.fileapex.cli.ipc.CliIpcDaemon
import com.fileapex.domain.launch.DesktopLaunchInbox
import com.fileapex.domain.launch.DesktopLaunchRequest
import com.fileapex.platform.DesktopInstanceWire
import com.fileapex.platform.DesktopSingleInstance
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CliIpcAuthenticationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cliCommandsRequireTheAppCredentialAndASeparateProtocolProof() {
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), DesktopLaunchInbox()::offer))
        try {
            val key = DesktopInstanceWire.readDescriptor(temp.root).key()
            val port = checkNotNull(CliIpcDaemon.start(0, key))
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 5_000
                assertFalse(DesktopInstanceWire.clientAuthenticate(socket, key, "launch"))
            }
            assertTrue(CliIpcClient.isDaemonRunning(port, temp.root))
            assertEquals(0, CliIpcClient.forwardCommand(listOf("help"), temp.root.path, port, temp.root))
        } finally { CliIpcDaemon.stop(); owner.close() }
    }

    private fun withDaemon(requestTimeoutMs: Int, body: (port: Int, key: ByteArray) -> Unit) {
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), DesktopLaunchInbox()::offer))
        try {
            val key = DesktopInstanceWire.readDescriptor(temp.root).key()
            body(checkNotNull(CliIpcDaemon.start(0, key, requestTimeoutMs)), key)
        } finally { CliIpcDaemon.stop(); owner.close() }
    }

    @Test fun anAuthenticatedClientThatSendsNoRequestIsDropped() = withDaemon(300) { port, key ->
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            assertTrue(DesktopInstanceWire.clientAuthenticate(socket, key, "cli"))
            assertEquals(-1, socket.getInputStream().read())
        }
    }

    @Test fun anOversizedRequestLineIsRefusedWithoutBeingBuffered() = withDaemon(5_000) { port, key ->
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            assertTrue(DesktopInstanceWire.clientAuthenticate(socket, key, "cli"))
            val chunk = ByteArray(8192) { 'a'.code.toByte() }
            val closed = runCatching { repeat(256) { socket.getOutputStream().write(chunk); socket.getOutputStream().flush() } }
            val end = runCatching { socket.getInputStream().read() }
            assertTrue(closed.isFailure || end.getOrNull() == -1)
        }
    }
}
