package com.fileapex.domain.transfer

import com.fileapex.domain.transfer.core.PiecePlan
import org.junit.Assert.*
import org.junit.Test

class PiecePlanTest {
    private val mb = 1024 * 1024
    private val plan = PiecePlan(size = 3L * mb + 10, pieceBytes = mb)

    @Test fun lastPieceIsShort() {
        assertEquals(mb, plan.lengthAt(0)); assertEquals(mb, plan.lengthAt(2L * mb)); assertEquals(10, plan.lengthAt(3L * mb))
    }

    @Test fun resumeCountsWholePiecesOnly() {
        assertEquals(0L, plan.resumeOffset(0)); assertEquals(0L, plan.resumeOffset(mb - 1L))
        assertEquals(2L * mb, plan.resumeOffset(2L * mb + 5)); assertEquals(3L * mb, plan.resumeOffset(plan.size))
    }

    @Test fun aLongerPartStartsOver() = assertEquals(0L, plan.resumeOffset(plan.size + 1))

    @Test fun offsetValidity() {
        assertTrue(plan.isValidOffset(0)); assertTrue(plan.isValidOffset(3L * mb))
        assertFalse(plan.isValidOffset(1)); assertFalse(plan.isValidOffset(-1)); assertFalse(plan.isValidOffset(plan.size + mb))
    }

    @Test fun emptyFileNeedsOneByteBufferAndNoPieces() {
        val empty = PiecePlan(0, mb)
        assertEquals(1, empty.bufferBytes); assertEquals(0L, empty.resumeOffset(0)); assertTrue(empty.isValidOffset(0))
    }

    @Test fun smallFileBufferIsItsSize() = assertEquals(500, PiecePlan(500, mb).bufferBytes)
}
