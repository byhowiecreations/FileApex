package com.fileapex.membership

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How a device whose id changed reaches a member: both calls go to one peer, over the connection pinned to the key the cluster already trusts. A null answer means the peer is unreachable. */
interface IdentityChangeLink {
    suspend fun send(peerId: String, request: IdentityChangeRequest): IdentityChangeAnswer?
    suspend fun status(peerId: String, oldId: String, nonce: String): IdentityChangeAnswer?
}

/**
 * Runs on the device with the new id: asks the members one after another until one shows its user the code, then waits
 * for that user's answer. [state] carries the code for this device's own screen, where the user compares it.
 */
class IdentityChangeRequester(
    private val signer: EventSigner,
    private val previousId: String,
    private val newId: String,
    private val newE2eeKey: String,
    private val hardwareHash: String,
    private val peers: suspend () -> List<String>,
    private val link: IdentityChangeLink,
    private val nonce: () -> String,
    private val pause: suspend () -> Unit,
    private val maxPolls: Int
) {
    enum class Failure { NOBODY_ANSWERED, DECLINED, EXPIRED }

    sealed class State {
        object Idle : State()
        data class Asking(val peerId: String, val code: String) : State()
        data class Done(val peerId: String) : State()
        data class Failed(val reason: Failure) : State()
    }

    private val internal = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = internal.asStateFlow()

    suspend fun run(onState: (State) -> Unit = {}) {
        fun set(next: State) { internal.value = next; onState(next) }
        val request = IdentityChangeProof.request(signer, previousId, newId, newE2eeKey, hardwareHash, nonce())
        for (peer in peers()) {
            val answer = link.send(peer, request)
            if (answer == null || answer.status != "waiting") continue
            set(State.Asking(peer, answer.code))
            set(wait(peer, request))
            return
        }
        set(State.Failed(Failure.NOBODY_ANSWERED))
    }

    private suspend fun wait(peer: String, request: IdentityChangeRequest): State {
        repeat(maxPolls) {
            pause()
            when (link.status(peer, request.oldId, request.nonce)?.status) {
                "confirmed" -> return State.Done(peer)
                "declined" -> return State.Failed(Failure.DECLINED)
                "expired", "unknown" -> return State.Failed(Failure.EXPIRED)
            }
        }
        return State.Failed(Failure.EXPIRED)
    }
}
