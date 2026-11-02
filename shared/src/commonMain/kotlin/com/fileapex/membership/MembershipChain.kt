package com.fileapex.membership

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

@kotlinx.serialization.Serializable
enum class RecordKind { GENESIS, ADMIT, REMOVE, LEAVE, TLS_PIN }

/**
 * One signed record of the membership chain. [seq] is exactly the parent's plus one and [parentHash] is the parent's
 * [hash] (empty for the genesis), so a record fits one place in one history: it cannot be replayed, skipped or moved.
 * [tlsPin] is the subject's TLS pin where the kind carries one.
 */
@kotlinx.serialization.Serializable
data class ChainRecord(
    val seq: Long,
    val parentHash: String,
    val kind: RecordKind,
    val actor: String,
    val actorKey: String,
    val subject: String,
    val subjectKey: String,
    val tlsPin: String,
    val reason: String,
    val signature: String = ""
) {
    /** Every field is length-prefixed, so no two different records have the same bytes. */
    fun canonicalBytes(): ByteArray = ByteArrayOutputStream().also { out ->
        fun put(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        put(DOMAIN)
        put(seq.toString())
        listOf(parentHash, kind.name, actor, actorKey, subject, subjectKey, tlsPin, reason).forEach(::put)
    }.toByteArray()

    /** SHA-256 over the signed bytes and the signature, as lowercase hex: the id of this record and the parent hash of the next. */
    fun hash(): String = MessageDigest.getInstance("SHA-256").run {
        update(canonicalBytes())
        update(signature.toByteArray(Charsets.UTF_8))
        digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DOMAIN = "fileapex-membership-chain-v1"
    }
}

object ChainRecords {
    fun sign(signer: EventSigner, unsigned: ChainRecord): ChainRecord {
        require(unsigned.actor == signer.deviceId && unsigned.actorKey == signer.publicKey) { "a device signs only as itself" }
        return unsigned.copy(signature = signer.sign(unsigned.canonicalBytes()))
    }

    fun verify(record: ChainRecord): Boolean = try {
        record.signature.isNotEmpty() && Signature.getInstance(EcEventSigner.ALGORITHM).run {
            initVerify(MembershipKeys.decode(record.actorKey))
            update(record.canonicalBytes())
            verify(Base64.getDecoder().decode(record.signature))
        }
    } catch (_: java.security.GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

data class ChainMember(val key: String, val tlsPin: String, val active: Boolean) {
    /** Admitted by another member for a device that never ran the chain: no key of its own, so nothing it "says" on the chain is signed by it. */
    val isLegacyAdmit: Boolean get() = key.isBlank() || key.startsWith(LEGACY_KEY_PREFIX)

    companion object {
        const val LEGACY_KEY_PREFIX = "legacy:"
    }
}

/** The membership as it stands after a record: [head] is that record's hash. A removed member stays listed, inactive, so its key cannot come back. */
data class ChainState(val seq: Long, val head: String, val members: Map<String, ChainMember>) {
    fun member(id: String): ChainMember? = members[id]
    fun activeIds(): List<String> = members.filterValues { it.active }.keys.toList()

    companion object {
        val EMPTY = ChainState(0L, "", emptyMap())
    }
}

sealed interface ChainVerdict {
    data class Accepted(val state: ChainState) : ChainVerdict
    data class Rejected(val why: String) : ChainVerdict
}

object MembershipChain {
    private val PIN = Regex("^[0-9a-f]{64}$")

    /** Checks [record] against [state] as it stands just before it: signature first, then place in the chain, then the rules of its kind. */
    fun apply(state: ChainState, record: ChainRecord): ChainVerdict {
        if (!ChainRecords.verify(record)) return no("bad signature")
        if (record.seq != state.seq + 1 || record.parentHash != state.head) return no("not the next record of this chain")
        if (record.kind == RecordKind.GENESIS) return genesis(state, record)
        val actor = state.member(record.actor)
        if (actor == null || !actor.active || actor.key != record.actorKey) return no("the actor is not an active member with this key")
        val members = state.members
        val next = when (record.kind) {
            RecordKind.ADMIT -> {
                val known = members[record.subject]
                if (record.subject == record.actor || record.subjectKey.isBlank()) return no("nothing to admit")
                val keyless = record.subjectKey.startsWith(ChainMember.LEGACY_KEY_PREFIX)
                if (known?.active == true) {
                    // A member with a key of its own is never rebound. A key-less one is bound to the real key it now holds.
                    if (!known.isLegacyAdmit || keyless) return no("already a member")
                } else {
                    if (known != null && known.key == record.subjectKey) return no("a removed device needs a new key")
                    if (known != null && keyless) return no("a removed device needs a real new key")
                }
                if (!pinOk(record.tlsPin)) return no("bad pin")
                members + (record.subject to ChainMember(record.subjectKey, record.tlsPin, true))
            }
            RecordKind.REMOVE -> {
                val target = members[record.subject]
                if (target == null || !target.active || record.subject == record.actor) return no("no active member to remove")
                if (record.reason != MembershipReason.USER_REMOVED) return no("a removal says user_removed")
                members + (record.subject to target.copy(active = false))
            }
            RecordKind.LEAVE -> {
                if (record.subject != record.actor || record.reason != MembershipReason.LEFT_CLUSTER) return no("a device leaves only itself")
                members + (record.subject to actor.copy(active = false))
            }
            RecordKind.TLS_PIN -> {
                if (record.subject != record.actor || !PIN.matches(record.tlsPin)) return no("a member announces only its own pin")
                members + (record.subject to actor.copy(tlsPin = record.tlsPin))
            }
            RecordKind.GENESIS -> return no("genesis")
        }
        if (next.values.none { it.active }) return no("the last active member stays")
        return ChainVerdict.Accepted(ChainState(record.seq, record.hash(), next))
    }

    private fun genesis(state: ChainState, record: ChainRecord): ChainVerdict {
        if (state.seq != 0L || record.actor != record.subject || record.actorKey != record.subjectKey) return no("bad genesis")
        if (!pinOk(record.tlsPin)) return no("bad pin")
        return ChainVerdict.Accepted(
            ChainState(1L, record.hash(), mapOf(record.subject to ChainMember(record.subjectKey, record.tlsPin, true)))
        )
    }

    /**
     * Two records with one parent are a fork. Every device picks the same one: a removal beats a record that is not,
     * otherwise the lower hash wins.
     */
    fun preferred(a: ChainRecord, b: ChainRecord): ChainRecord {
        require(a.parentHash == b.parentHash && a.seq == b.seq) { "not a fork" }
        val aRemoves = a.kind == RecordKind.REMOVE
        val bRemoves = b.kind == RecordKind.REMOVE
        return when {
            aRemoves != bRemoves -> if (aRemoves) a else b
            a.hash() <= b.hash() -> a
            else -> b
        }
    }

    private fun pinOk(pin: String) = pin.isEmpty() || PIN.matches(pin)

    private fun no(why: String): ChainVerdict = ChainVerdict.Rejected(why)
}
