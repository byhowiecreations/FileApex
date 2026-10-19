package com.fileapex.iroh

actual fun embeddedIrohEngine(): IrohEngine? = object : IrohEngine {
    override suspend fun start(secretKey: ByteArray, relay: IrohRelayConfig?): IrohLinkHandle =
        IrohEndpointLink.bind(secretKey = secretKey, customRelay = relay)
}
