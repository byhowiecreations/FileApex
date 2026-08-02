package com.fileapex.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.fileapex.platform.androidApplicationContextOrNull

actual object FileApexMdnsAdvertiser {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    actual fun start(port: Int, deviceId: String) {
        stop()
        val context = androidApplicationContextOrNull() ?: return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = FileApexMdns.serviceNameFor(deviceId)
            serviceType = FileApexMdns.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                println("FileApexMdnsAdvertiser: registered ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                println("FileApexMdnsAdvertiser: registration failed code=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            println("FileApexMdnsAdvertiser: registerService failed — ${error.message}")
        }
    }

    actual fun stop(fast: Boolean) {
        val manager = nsdManager
        val listener = registrationListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
        nsdManager = null
        registrationListener = null
    }
}
