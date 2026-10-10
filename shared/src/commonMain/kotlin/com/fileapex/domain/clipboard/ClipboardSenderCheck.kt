package com.fileapex.domain.clipboard

import com.fileapex.data.db.PairedDeviceEntity

object ClipboardSenderCheck {
    /**
     * The key in a clipboard request is a claim. It is accepted only when it equals the key on record for a
     * paired, non-removed device (stored at pairing, else from that device's own cloud record).
     * Throws `clipboard_sender_unknown` or `clipboard_sender_unverified`; returns the recorded key.
     */
    fun verify(device: PairedDeviceEntity?, cloudKey: String, claimedKey: String): String {
        if (device == null || device.isRemoved) error("clipboard_sender_unknown")
        val recorded = device.publicKey.trim().ifEmpty { cloudKey.trim() }
        if (recorded.isEmpty() || recorded != claimedKey.trim()) error("clipboard_sender_unverified")
        return recorded
    }
}
