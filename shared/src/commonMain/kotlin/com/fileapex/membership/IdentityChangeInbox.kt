package com.fileapex.membership

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The id changes this member was asked to take, until the user compares the code and decides. A request that fails
 * [IdentityChangeProof.verify] never reaches the user; one nobody answers expires after [ttlMs].
 */
class IdentityChangeInbox(
    private val member: suspend (deviceId: String) -> ChainMember?,
    private val knownHardware: suspend (deviceId: String) -> String,
    private val oldIdAnswers: suspend (deviceId: String) -> Boolean,
    private val confirm: suspend (IdentityChangeRequest) -> ChainVerdict,
    private val now: () -> Long,
    private val ttlMs: Long
) {
    class Pending(val request: IdentityChangeRequest, val code: String, val receivedAt: Long)

    sealed class Intake {
        data class Waiting(val code: String) : Intake()
        data class Refused(val reason: IdentityChangeProof.Refusal) : Intake()
    }

    enum class Status { WAITING, CONFIRMED, DECLINED, EXPIRED, UNKNOWN }

    private val internal = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = internal.asStateFlow()
    private val finished = ConcurrentHashMap<String, Pair<String, Status>>()

    suspend fun receive(request: IdentityChangeRequest, connectionPin: String): Intake {
        expire()
        val refusal = IdentityChangeProof.verify(request, member(request.oldId), connectionPin, knownHardware(request.oldId), oldIdAnswers(request.oldId))
        if (refusal != null) return Intake.Refused(refusal)
        val code = IdentityChangeProof.code(request)
        finished.remove(request.oldId)
        internal.update { list -> list.filterNot { it.request.oldId == request.oldId } + Pending(request, code, now()) }
        return Intake.Waiting(code)
    }

    fun status(oldId: String, nonce: String): Status {
        expire()
        if (internal.value.any { it.request.oldId == oldId && it.request.nonce == nonce }) return Status.WAITING
        return finished[oldId]?.takeIf { it.first == nonce }?.second ?: Status.UNKNOWN
    }

    suspend fun accept(oldId: String): ChainVerdict {
        expire()
        val item = internal.value.firstOrNull { it.request.oldId == oldId } ?: return ChainVerdict.Rejected("no such request")
        internal.update { list -> list.filterNot { it === item } }
        val verdict = confirm(item.request)
        finished[oldId] = item.request.nonce to if (verdict is ChainVerdict.Accepted) Status.CONFIRMED else Status.DECLINED
        return verdict
    }

    fun decline(oldId: String) {
        val item = internal.value.firstOrNull { it.request.oldId == oldId } ?: return
        internal.update { list -> list.filterNot { it === item } }
        finished[oldId] = item.request.nonce to Status.DECLINED
    }

    private fun expire() {
        val cutoff = now() - ttlMs
        val gone = internal.value.filter { it.receivedAt < cutoff }
        if (gone.isEmpty()) return
        internal.update { list -> list.filterNot { it in gone } }
        gone.forEach { finished[it.request.oldId] = it.request.nonce to Status.EXPIRED }
    }
}

/** What a member answers to an id change request or a question about it. [status] is waiting, confirmed, declined, expired, unknown or refused. */
@kotlinx.serialization.Serializable
data class IdentityChangeAnswer(val status: String, val code: String = "", val reason: String = "")
