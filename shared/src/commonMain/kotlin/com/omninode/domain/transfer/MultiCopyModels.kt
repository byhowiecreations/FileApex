package com.omninode.domain.transfer

/**
 * One selectable destination in the Multi Copy picker.
 * Local is optional — files go there only when the user selects This device.
 */
data class MultiCopyDeviceOption(
    val deviceId: String,
    val deviceName: String,
    val isLocal: Boolean,
    val host: String,
    val port: Int,
    val destinationRoot: String
)

sealed interface MultiCopySource {
    val fileName: String
    val sizeBytes: Long
    val absolutePath: String

    data class Local(
        override val fileName: String,
        override val sizeBytes: Long,
        override val absolutePath: String
    ) : MultiCopySource

    data class Remote(
        override val fileName: String,
        override val sizeBytes: Long,
        override val absolutePath: String,
        val host: String,
        val port: Int
    ) : MultiCopySource
}

sealed interface MultiCopyDestination {
    val deviceId: String
    val deviceName: String

    data class LocalDevice(
        override val deviceId: String,
        override val deviceName: String,
        val absolutePath: String
    ) : MultiCopyDestination

    data class RemoteDevice(
        override val deviceId: String,
        override val deviceName: String,
        val host: String,
        val port: Int,
        val absolutePath: String
    ) : MultiCopyDestination
}

data class MultiCopyResult(
    val fileName: String,
    val succeededDeviceIds: Set<String>,
    val failures: Map<String, String>
)
