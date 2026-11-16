package com.fileapex.domain.link

import com.fileapex.util.TimeUtils
import java.util.concurrent.ConcurrentHashMap

/** Peers that said, in a health answer, which device they are showing, so a guess does not pick a peer that is taken. */
class LinkedElsewhere {
    private class Note(val linkedTo: String?, val expiresAtEpochMs: Long)

    private val notes = ConcurrentHashMap<String, Note>()

    fun note(peerId: String, linkedTo: String?, now: Long = TimeUtils.now()) {
        notes[peerId] = Note(linkedTo?.trim()?.takeIf { it.isNotEmpty() }, now + PairLink.LEASE_MS)
    }

    fun excludedFor(selfId: String, now: Long = TimeUtils.now()): Set<String> =
        notes.filter { (_, note) -> now < note.expiresAtEpochMs && note.linkedTo != null && note.linkedTo != selfId }.keys

    companion object {
        val shared = LinkedElsewhere()
    }
}
