package com.omninode.domain.share

/**
 * Files ready for the Android system Share → device picker flow.
 * Paths are already staged on disk for [com.omninode.domain.transfer.TransferManager].
 */
data class IncomingSharePayload(
    val sessionId: String,
    val files: List<IncomingShareFile>
)

data class IncomingShareFile(
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long
)
