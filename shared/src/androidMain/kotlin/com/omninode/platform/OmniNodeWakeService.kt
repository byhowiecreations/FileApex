package com.omninode.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.omninode.network.WakeProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OmniNodeWakeService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var socket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            listenForWake()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { socket?.close() }
        socket = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun listenForWake() {
        val buffer = ByteArray(256)
        while (serviceJob.isActive) {
            val activeSocket = openSocket()
            if (activeSocket == null) {
                delay(1_000)
                continue
            }
            try {
                while (serviceJob.isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)
                    val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (payload == WakeProtocol.PAYLOAD) {
                        startShareServer()
                    }
                }
            } catch (_: SocketException) {
                if (!serviceJob.isActive) return
            } finally {
                runCatching { activeSocket.close() }
                if (socket === activeSocket) {
                    socket = null
                }
            }
        }
    }

    private fun openSocket(): DatagramSocket? {
        return runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress("0.0.0.0", WakeProtocol.PORT))
                socket = this
            }
        }.getOrNull()
    }

    private fun startShareServer() {
        val intent = Intent().setClassName(packageName, FILE_SHARE_SERVER_SERVICE)
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private const val FILE_SHARE_SERVER_SERVICE =
            "com.omninode.network.FileShareServerService"
    }
}
