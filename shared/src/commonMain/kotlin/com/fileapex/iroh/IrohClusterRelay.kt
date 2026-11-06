package com.fileapex.iroh

import com.fileapex.data.settings.AppSettings

/** The relay choice a device sends to and takes from its paired devices. */
object IrohClusterRelay {
    /** Our shared relay choice, or null when it was never set, so a device that never used the feature sends nothing. */
    fun own(settings: AppSettings): IrohRelaySetting? = settings.irohRelaySetting().takeIf { it.changedAtMs > 0L }

    /** Stores [remote] when it is newer and valid; the endpoint then restarts through the settings flow. */
    fun adopt(settings: AppSettings, remote: IrohRelaySetting?, nowMs: Long): Boolean {
        val adopted = IrohRelaySetting.adopt(settings.irohRelaySetting(), remote, nowMs) ?: return false
        settings.applyClusterIrohRelay(adopted)
        IrohLog.add("relay setting changed by a paired device")
        return true
    }
}
