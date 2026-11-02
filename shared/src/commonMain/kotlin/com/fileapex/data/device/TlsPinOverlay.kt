package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity

/**
 * The device rows as every reader sees them: the pin, rotation pin and TLS port come from the pin store (`member_local`)
 * and nowhere else, and the pin of a removed member is not shown; the store keeps it as a revoked entry.
 */
object TlsPinOverlay {
    fun apply(devices: List<PairedDeviceEntity>, entries: List<TlsPinEntry>): List<PairedDeviceEntity> {
        val active = entries.filter { !it.revoked && TlsPinStore.FORMAT.matches(it.pin) }.associateBy { it.deviceId }
        return devices.map { device ->
            val entry = active[device.deviceId]
            if (entry == null) {
                if (device.tlsPin.isEmpty() && device.tlsPinAlt.isEmpty() && device.tlsPort == 0) device
                else device.copy(tlsPin = "", tlsPinAlt = "", tlsPort = 0)
            } else {
                device.copy(
                    tlsPin = entry.pin,
                    tlsPinAlt = entry.alt.takeIf { TlsPinStore.FORMAT.matches(it) }.orEmpty(),
                    tlsPort = entry.port.takeIf { it in 1..65535 } ?: (device.port + 1)
                )
            }
        }
    }
}
