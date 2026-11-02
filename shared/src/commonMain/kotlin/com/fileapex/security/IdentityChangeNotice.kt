package com.fileapex.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IdentityChange(val previousId: String, val currentId: String)

/**
 * A device whose id changed because its identity key was lost or replaced says so until the cluster holds its new id as
 * an active member. Other members know it under the old id and refuse the new one, so the user has to be told.
 */
object IdentityChangeNotice {
    private val state = MutableStateFlow<IdentityChange?>(null)
    val pending: StateFlow<IdentityChange?> = state.asStateFlow()

    private val requestState = MutableStateFlow<com.fileapex.membership.IdentityChangeRequester.State>(com.fileapex.membership.IdentityChangeRequester.State.Idle)

    /** Where this device's request to continue under the new id stands, for the screen that shows the code to compare. */
    val request: StateFlow<com.fileapex.membership.IdentityChangeRequester.State> = requestState.asStateFlow()

    fun requesting(next: com.fileapex.membership.IdentityChangeRequester.State) {
        requestState.value = next
    }

    /** Removed rows stay in the table, so they must not count as a cluster. */
    fun hasCluster(devices: List<com.fileapex.data.db.PairedDeviceEntity>): Boolean = devices.any { !it.isRemoved }

    /** Only a device that has a cluster is told: with none, no other device could refuse the new id, so the old one is forgotten instead. */
    fun report(previousId: String, currentId: String, inCluster: Boolean = true, forget: () -> Unit = {}) {
        val previous = previousId.trim()
        val current = currentId.trim()
        if (!inCluster) {
            if (previous.isNotEmpty()) forget()
            state.value = null
            return
        }
        state.value = if (previous.isNotEmpty() && previous != current) IdentityChange(previous, current) else null
        if (state.value != null) println("IdentityChange: this device's id changed from $previous to $current")
    }

    /** The user saw the notice; the remembered previous id stays until the change is settled. */
    fun acknowledge() {
        state.value = null
    }

    /** True when the change was settled and the remembered id was cleared through [clear]. */
    fun settled(isActiveMember: Boolean, storedPrevious: String, clear: () -> Unit): Boolean {
        if (!isActiveMember || storedPrevious.isBlank()) return false
        clear()
        state.value = null
        requestState.value = com.fileapex.membership.IdentityChangeRequester.State.Idle
        return true
    }
}
