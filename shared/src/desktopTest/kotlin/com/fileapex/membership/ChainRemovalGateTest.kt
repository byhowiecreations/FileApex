package com.fileapex.membership

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRemovalGateTest {
    private fun state(vararg members: Pair<String, Boolean>) =
        ChainState(1L, "head", members.associate { (id, active) -> id to ChainMember("key-$id", "", active) })

    @Test
    fun aRemovalIsConfirmedOnlyWhenTheChainHasTheDeviceAsNoLongerActive() = runBlocking {
        val gate = ChainRemovalGate { state("a" to true, "gone" to false) }
        assertTrue(gate.confirms("gone"))
        assertFalse(gate.confirms("a"))
        assertFalse(gate.confirms("never-heard-of"))
    }

    @Test
    fun withoutAChainNoRemovalIsConfirmed() = runBlocking {
        assertFalse(ChainRemovalGate { null }.confirms("gone"))
        assertFalse(ChainRemovalGate { ChainState.EMPTY }.confirms("gone"))
    }
}
