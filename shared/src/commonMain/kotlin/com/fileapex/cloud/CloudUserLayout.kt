package com.fileapex.cloud

/**
 * Shared cloud document for paired-device list order (`users/{uid}/preferences/layout`).
 */
data class CloudUserLayout(
    val deviceOrderIds: List<String>,
    val updatedAtEpochMs: Long
)
