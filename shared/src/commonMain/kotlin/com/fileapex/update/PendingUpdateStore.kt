package com.fileapex.update

/**
 * Persists a pending GitHub update offer across process death so notification taps
 * can still open the update sheet / start install.
 */
expect object PendingUpdateStore {
    fun save(offer: PendingUpdateOffer?)
    fun load(): PendingUpdateOffer?
}
