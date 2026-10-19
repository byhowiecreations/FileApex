package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity

/** What the host's pairing prompt can say about the device asking to join, from its hardware hash. */
sealed interface PairingHint {
    data object None : PairingHint

    /** The same hardware is already paired under another id: this is a reinstall, and pairing replaces the old entry. */
    data class SameHardware(val names: List<String>, val ids: List<String>) : PairingHint

    /** The same hardware was removed from this cluster earlier. */
    data object RemovedBefore : PairingHint

    /** It uses the name of a paired device but is not the same hardware: the user should look twice. */
    data class SameNameDifferentHardware(val name: String) : PairingHint
}

object PairingHints {
    fun evaluate(
        joinerId: String,
        joinerName: String,
        joinerHash: String,
        active: List<PairedDeviceEntity>,
        removed: List<PairedDeviceEntity>
    ): PairingHint {
        val hash = joinerHash.trim()
        if (hash.isEmpty()) return PairingHint.None
        val twins = active.filter { it.deviceId != joinerId && it.hardwareHash == hash }
        if (twins.isNotEmpty()) return PairingHint.SameHardware(twins.map { it.deviceName }, twins.map { it.deviceId })
        if (removed.any { it.deviceId != joinerId && it.hardwareHash == hash }) return PairingHint.RemovedBefore
        val sameName = active.firstOrNull { row ->
            row.deviceId != joinerId && row.hardwareHash.isNotEmpty() && row.hardwareHash != hash &&
                row.deviceName.trim().equals(joinerName.trim(), ignoreCase = true)
        }
        return if (sameName != null) PairingHint.SameNameDifferentHardware(sameName.deviceName) else PairingHint.None
    }
}
