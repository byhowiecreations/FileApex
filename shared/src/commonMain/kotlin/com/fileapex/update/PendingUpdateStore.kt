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
    fun setNoteInstallStatus(noteId: String, status: String)
    fun getNoteInstallStatus(noteId: String): String?
    fun isNoteInstalled(noteId: String): Boolean
    fun setLastAttemptedNoteId(noteId: String)
    fun getLastAttemptedNoteId(): String
    fun setLastAttemptedTransactionId(transactionId: String)
    fun getLastAttemptedTransactionId(): String
    fun setTransactionInstallStatus(transactionId: String, status: String)
    fun getTransactionInstallStatus(transactionId: String): String?
    fun isTransactionInstalled(transactionId: String): Boolean
    fun saveTransactionRecord(record: com.fileapex.network.TransferTransactionRecord)
    fun getTransactionRecord(transactionId: String): com.fileapex.network.TransferTransactionRecord?
    fun findTransactionByFilePath(filePath: String): com.fileapex.network.TransferTransactionRecord?
    fun markTransactionInstallAttempted(transactionId: String)
    fun markTransactionInstalled(transactionId: String)
    fun purgeTransaction(transactionId: String)
    fun deleteUpdateApkAndCompleteTransaction(transactionId: String): Boolean
}
