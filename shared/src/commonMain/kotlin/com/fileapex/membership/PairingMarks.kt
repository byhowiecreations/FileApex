package com.fileapex.membership

/** What this device remembers about its own in-person pairing, so a restart between the pairing and the next tick does not lose it. */
interface PairingMarks {
    /** When the user last paired this device in person, 0 when never or already used. */
    suspend fun pairedAt(): Long
    suspend fun markPaired(at: Long)
    suspend fun clear()
}

class InMemoryPairingMarks : PairingMarks {
    @Volatile
    private var at = 0L

    override suspend fun pairedAt(): Long = at
    override suspend fun markPaired(at: Long) { this.at = at }
    override suspend fun clear() { at = 0L }
}
