package com.fileapex.di

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.MemberStore
import com.fileapex.data.db.MembershipMetaEntity
import com.fileapex.data.db.RoomChainStore
import com.fileapex.membership.ChainVerdict
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.MembershipKeys
import com.fileapex.membership.MembershipReason
import com.fileapex.membership.RecordKind
import java.security.KeyPairGenerator

/** Puts a real chain in a real Room database and the pins of its members beside it, so `members` is built the way the app builds it. */
internal class ChainSeed(val db: FileApexDatabase, val selfId: String = "mac", val rootId: String = selfId) {
    class Dev(val id: String) {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val devs = HashMap<String, Dev>()
    val log = MembershipChainLog(RoomChainStore(db.chainDao())) { selfId }

    fun dev(id: String) = devs.getOrPut(id) { Dev(id) }

    /** The genesis of the cluster ([rootId] wrote it, this device unless a test says otherwise); the roster is rebuilt from it. */
    suspend fun start(): ChainSeed {
        db.membershipDao().putMeta(MembershipMetaEntity(MemberStore.SELF_ID_KEY, selfId))
        val root = dev(rootId)
        accept(log.append(root.signer, RecordKind.GENESIS, rootId, root.key, root.pin, MembershipReason.PAIRED))
        refresh()
        return this
    }

    /** [id] is admitted with a key of its own, and this device trusts [pin] for it. */
    suspend fun admit(id: String, pin: String = dev(id).pin, port: Int = 8081, key: String = dev(id).key, trustPin: Boolean = true, project: Boolean = true) {
        accept(log.append(dev(rootId).signer, RecordKind.ADMIT, id, key, pin, MembershipReason.PAIRED))
        if (trustPin) db.tlsPinDao().putTls(id, pin, "", port)
        if (project) refresh()
    }

    /** [id] never ran the chain: admitted without a key. */
    suspend fun admitKeyless(id: String, pin: String, port: Int = 8081) =
        admit(id, pin, port, key = "legacy:$id")

    /** The REMOVE record; with [project] false the roster is left as it was, as when the record has just arrived from a peer. */
    suspend fun remove(id: String, by: String = rootId, project: Boolean = true) {
        val member = log.view().state.member(id)!!
        accept(log.append(dev(by).signer, RecordKind.REMOVE, id, member.key, member.tlsPin, MembershipReason.USER_REMOVED))
        if (project) refresh()
    }

    /** [id] leaves the cluster on its own. */
    suspend fun leave(id: String, project: Boolean = true) {
        val member = log.view().state.member(id)!!
        accept(log.append(dev(id).signer, RecordKind.LEAVE, id, member.key, member.tlsPin, MembershipReason.LEFT_CLUSTER))
        if (project) refresh()
    }

    private fun accept(verdict: ChainVerdict) = check(verdict is ChainVerdict.Accepted) { "the chain refused the seed record: $verdict" }

    suspend fun refresh() = db.membershipDao().refreshMembers()
}
