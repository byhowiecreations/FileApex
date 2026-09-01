package com.fileapex.update

/**
 * Persists a pending GitHub update offer across process death so notification taps
 * can still open the update sheet / start install.
 */
expect object PendingUpdateStore {
    fun save(offer: PendingUpdateOffer?)
    fun load(): PendingUpdateOffer?
    fun markProcessedNote(noteId: String, timestampEpochMs: Long = 0L, signature: String = "")
    fun isNoteProcessed(noteId: String, timestampEpochMs: Long = 0L, signature: String = ""): Boolean
    fun removeProcessedNote(noteId: String)
    fun markProcessedFile(signature: String)
    fun isFileProcessed(signature: String): Boolean
    fun removeProcessedFile(signature: String)
}
