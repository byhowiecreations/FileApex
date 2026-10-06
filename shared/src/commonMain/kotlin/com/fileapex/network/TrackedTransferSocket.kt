package com.fileapex.network

import com.fileapex.domain.transfer.TransferActivityGuard
import java.io.IOException
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Keeps [socket] on the cancel list for the whole call.
 * Closing it is what unblocks a write that is waiting on a slow peer.
 */
internal suspend fun <T> withTrackedTransferSocket(socket: Socket, block: suspend () -> T): T {
    TransferActivityGuard.trackTransferSocket(socket)
    try {
        return block()
    } catch (error: IOException) {
        if (TransferActivityGuard.transferCancelRequested() || !currentCoroutineContext().isActive) {
            throw CancellationException("transfer cancelled")
        }
        throw error
    } finally {
        TransferActivityGuard.releaseTransferSocket(socket)
        runCatching { socket.close() }
    }
}
