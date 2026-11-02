package com.fileapex.platform

import com.fileapex.domain.launch.DesktopLaunchRequest
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Holds process ownership independently of database, share-server and GUI readiness. The lock file is never unlinked. */
class DesktopSingleInstance private constructor(
    private val directory: File,
    private val channel: FileChannel,
    private val lease: FileLock
) : Closeable {
    private val key = SecureRandom().generateSeed(32)
    private val server = ServerSocket(0, 8, LOOPBACK)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "FileApex-LaunchClient").apply { isDaemon = true } })
    @Volatile private var stopped = false

    private fun listen(initial: DesktopLaunchRequest, receive: (DesktopLaunchRequest) -> Boolean) {
        if (!receive(initial)) throw IOException("Initial app request was refused")
        LocalApiFile.publish(DesktopInstanceWire.descriptorFile(directory), Json.encodeToString(
            DesktopInstanceDescriptor(server.localPort, Base64.getEncoder().encodeToString(key))))
        Thread({
            try {
                while (!stopped) {
                    val client = server.accept()
                    clients.add(client)
                    try { workers.execute { handle(client, receive) } }
                    catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
                }
            } catch (e: SocketException) {
                if (!stopped) println("DesktopInstance: listener stopped :: ${e::class.simpleName}")
            } catch (e: IOException) {
                println("DesktopInstance: listener failed :: ${e::class.simpleName}")
            }
        }, "FileApex-LaunchListener").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket, receive: (DesktopLaunchRequest) -> Boolean) {
        try {
            client.use {
                client.soTimeout = 1500
                DesktopInstanceWire.deadline(client) {
                    if (!DesktopInstanceWire.serverAuthenticate(client, key, "launch")) return@deadline
                    val request = Json.decodeFromString<DesktopLaunchRequest>(DesktopInstanceWire.readFrame(client).decodeToString())
                    client.getOutputStream().apply { write(if (receive(request)) 1 else 0); flush() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            println("DesktopInstance: launch request ended :: ${e::class.simpleName}")
        } finally { clients.remove(client) }
    }

    /** App shutdown stops requests but retains ownership until the JVM exits, while databases may still be closing. */
    fun stopForwarding() {
        stopped = true
        attempt("listener") { server.close() }
        clients.forEach { client -> attempt("client") { client.close() } }
        workers.shutdownNow()
        attempt("descriptor") { Files.deleteIfExists(DesktopInstanceWire.descriptorFile(directory).toPath()) }
    }

    /** Every shutdown step runs even when an earlier one fails; the failure is logged by type only. */
    private inline fun attempt(step: String, block: () -> Unit) {
        try { block() } catch (e: IOException) { println("DesktopInstance: could not close $step :: ${e::class.simpleName}") }
    }

    override fun close() = synchronized(owners) {
        try { stopForwarding() } finally {
            owners.remove(directory.canonicalPath, this)
            try { lease.release() } finally { channel.close(); key.fill(0) }
        }
    }

    companion object {
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val owners = HashMap<String, DesktopSingleInstance>()

        fun start(directory: File, request: DesktopLaunchRequest, receive: (DesktopLaunchRequest) -> Boolean): DesktopSingleInstance? {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create app data directory")
            val path = directory.canonicalPath
            FileSystems.getDefault().newWatchService().use { watch ->
                directory.toPath().register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
                val began = System.nanoTime()
                while (System.nanoTime() - began < TimeUnit.SECONDS.toNanos(10)) {
                    val owner = synchronized(owners) {
                        if (path in owners) null else claim(directory)?.also { owners[path] = it }
                    }
                    if (owner != null) {
                        try { owner.listen(request, receive); return owner }
                        catch (e: Exception) { owner.close(); throw e }
                    }
                    val accepted = try { forward(directory, request) } catch (_: IOException) { null }
                    if (accepted == true) return null
                    if (accepted == false) throw IOException("The running app cannot accept this request")
                    // File events wake startup waiters; the bounded poll also observes an owner's crash releasing its OS lock.
                    watch.poll(250, TimeUnit.MILLISECONDS)?.let { it.pollEvents(); it.reset() }
                }
            }
            throw IOException("The existing app did not accept the launch; no second app was started")
        }

        /** Takes the OS lock for [directory], or returns null while another process holds it; the channel never outlives a failed claim. */
        internal fun claim(directory: File, tryLock: (FileChannel) -> FileLock? = { it.tryLock() }): DesktopSingleInstance? {
            val opened = FileChannel.open(File(directory, "app-instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            var owner: DesktopSingleInstance? = null
            var lock: FileLock? = null
            try {
                lock = try { tryLock(opened) } catch (_: OverlappingFileLockException) { null }
                if (lock != null) owner = DesktopSingleInstance(directory, opened, lock)
            } finally {
                if (owner == null) {
                    try { lock?.release() } finally { opened.close() }
                }
            }
            return owner
        }

        private fun forward(directory: File, request: DesktopLaunchRequest): Boolean {
            val descriptor = DesktopInstanceWire.readDescriptor(directory)
            Socket().use { socket ->
                socket.soTimeout = 1500
                socket.connect(InetSocketAddress(LOOPBACK, descriptor.port), 1000)
                return DesktopInstanceWire.deadline(socket) {
                    if (!DesktopInstanceWire.clientAuthenticate(socket, descriptor.key(), "launch")) throw IOException("App authentication failed")
                    DesktopInstanceWire.writeFrame(socket, Json.encodeToString(request).encodeToByteArray())
                    when (socket.getInputStream().read()) { 1 -> true; 0 -> false; else -> throw IOException("App handoff was interrupted") }
                }
            }
        }

        fun stopLiveForwarding() {
            synchronized(owners) { owners.values.toList() }.forEach { it.stopForwarding() }
        }
    }
}
