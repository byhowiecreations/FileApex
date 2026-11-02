package com.fileapex.platform

import com.fileapex.domain.launch.DesktopLaunchInbox
import com.fileapex.domain.launch.DesktopLaunchRequest
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URLClassLoader
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSingleInstanceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun laterLaunchForwardsBeforeAnySecondOwnerCanStart() {
        val inbox = DesktopLaunchInbox()
        val first = DesktopLaunchRequest(cwd = temp.root.path)
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, first, inbox::offer))
        try {
            val next = DesktopLaunchRequest(args = listOf("missing 文件.txt", "fileapex://friend?v=1"), cwd = temp.root.path)
            assertNull(DesktopSingleInstance.start(temp.root, next, inbox::offer))
            assertEquals(listOf(first.id, next.id), inbox.pending.value.map { it.id })
            assertNull(DesktopSingleInstance.start(temp.root, next, inbox::offer))
            assertEquals(2, inbox.pending.value.size)
        } finally { owner.close() }
        checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), DesktopLaunchInbox()::offer)).close()
    }

    @Test fun rejectionNeverFallsBackToStartingAnotherOwner() {
        val inbox = DesktopLaunchInbox()
        var accepting = true
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path)) { accepting && inbox.offer(it) })
        try {
            accepting = false
            assertTrue(runCatching { DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), inbox::offer) }.exceptionOrNull() is IOException)
            accepting = true
            assertNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), inbox::offer))
            assertEquals(2, inbox.pending.value.size)
        } finally { owner.close() }
    }

    @Test fun aConnectionWithoutThePrivateCredentialCannotSubmitALaunch() {
        val inbox = DesktopLaunchInbox()
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), inbox::offer))
        try {
            val descriptor = DesktopInstanceWire.readDescriptor(temp.root)
            Socket(InetAddress.getByName("127.0.0.1"), descriptor.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(ByteArray(32) { 1 })
                assertEquals(32, socket.getInputStream().readNBytes(32).size)
                socket.getOutputStream().write(ByteArray(32))
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(1, inbox.pending.value.size)
        } finally { owner.close() }
    }

    private fun child(mode: String): Process {
        val sources = listOf(javaClass, DesktopSingleInstance::class.java, kotlin.Unit::class.java,
            kotlinx.coroutines.flow.MutableStateFlow::class.java, kotlinx.serialization.json.Json::class.java,
            kotlinx.serialization.Serializable::class.java, org.bouncycastle.crypto.agreement.X25519Agreement::class.java)
            .map { it.protectionDomain.codeSource.location }
        val inherited = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
        val classpath = (sources.asSequence() + inherited).filter { it.protocol == "file" }
            .map { File(it.toURI()).path }.distinct().joinToString(File.pathSeparator)
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val arguments = temp.newFile("child-${java.util.UUID.randomUUID()}.args")
        arguments.writeText(listOf("-Djava.awt.headless=true", "-cp", quote(classpath), DesktopInstanceProcessFixture::class.java.name, quote(temp.root.path), mode).joinToString("\n"))
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "@${arguments.path}").redirectErrorStream(true).start()
    }

    @Test fun anotherJvmForwardsAndACrashedOwnerCanBeReplaced() {
        val inbox = DesktopLaunchInbox()
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), inbox::offer))
        try {
            val follower = child("forward")
            try {
                assertTrue(follower.waitFor(10, TimeUnit.SECONDS))
                assertEquals("FORWARDED", follower.inputStream.bufferedReader().readText().trim())
                assertEquals(0, follower.exitValue())
                assertEquals(2, inbox.pending.value.size)
            } finally { follower.destroyForcibly() }
        } finally { owner.close() }
        val crashed = child("hold")
        try {
            assertEquals("OWNER", crashed.inputStream.bufferedReader().readLine())
            crashed.destroyForcibly()
            assertTrue(crashed.waitFor(10, TimeUnit.SECONDS))
            checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), DesktopLaunchInbox()::offer)).close()
        } finally { crashed.destroyForcibly() }
    }

    @Test fun aFailedLockAttemptClosesTheChannelItOpened() {
        var opened: java.nio.channels.FileChannel? = null
        val failure = runCatching {
            DesktopSingleInstance.claim(temp.root) { channel -> opened = channel; throw IOException("lock unavailable") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(checkNotNull(opened).isOpen)
    }

    @Test fun stoppingForwardingFinishesEveryStepWhenOneClientWillNotClose() {
        val owner = checkNotNull(DesktopSingleInstance.start(temp.root, DesktopLaunchRequest(cwd = temp.root.path), DesktopLaunchInbox()::offer))
        try {
            @Suppress("UNCHECKED_CAST")
            val clients = DesktopSingleInstance::class.java.getDeclaredField("clients").apply { isAccessible = true }.get(owner) as MutableSet<Socket>
            clients += object : Socket() { override fun close() { throw IOException("stuck") } }
            owner.stopForwarding()
            val workers = DesktopSingleInstance::class.java.getDeclaredField("workers").apply { isAccessible = true }.get(owner) as java.util.concurrent.ExecutorService
            assertTrue(workers.isShutdown)
            assertFalse(DesktopInstanceWire.descriptorFile(temp.root).exists())
        } finally { owner.close() }
    }

    @Test fun simultaneousColdLaunchesElectExactlyOneOwner() {
        val first = child("hold")
        val second = child("hold")
        try {
            val results = listOf(first, second).map { it.inputStream.bufferedReader().readLine() }
            assertEquals(listOf("FORWARDED", "OWNER"), results.sortedBy { it })
        } finally {
            first.destroyForcibly(); second.destroyForcibly()
            assertTrue(first.waitFor(10, TimeUnit.SECONDS))
            assertTrue(second.waitFor(10, TimeUnit.SECONDS))
        }
    }
}

object DesktopInstanceProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args[0])
        val owner = DesktopSingleInstance.start(directory, DesktopLaunchRequest(args = listOf("child.txt"), cwd = directory.path), DesktopLaunchInbox()::offer)
        println(if (owner == null) "FORWARDED" else "OWNER")
        System.out.flush()
        if (owner != null) {
            if (args[1] == "hold") System.`in`.read()
            owner.close()
        }
    }
}
