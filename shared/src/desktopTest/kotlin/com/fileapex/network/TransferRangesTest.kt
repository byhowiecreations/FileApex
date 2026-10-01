package com.fileapex.network

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRangesTest {

    @Test
    fun planCoversWholeFileWithoutGaps() {
        val total = 1_000_003L
        val plan = TransferRanges.plan(total, 4)
        assertEquals(4, plan.size)
        assertEquals(0L, plan.first().start)
        assertEquals(total, plan.last().endExclusive)
        plan.zipWithNext().forEach { (a, b) -> assertEquals(a.endExclusive, b.start) }
    }

    @Test
    fun planNeverProducesEmptySegmentsForTinyFiles() {
        assertEquals(listOf(ByteSpan(0, 1), ByteSpan(1, 3)), TransferRanges.plan(3, 2))
        assertEquals(listOf(ByteSpan(0, 1)), TransferRanges.plan(1, 4))
    }

    @Test
    fun mergeJoinsOverlappingAndAdjacentSpans() {
        val merged = TransferRanges.merge(listOf(ByteSpan(10, 20), ByteSpan(0, 10), ByteSpan(15, 30), ByteSpan(40, 50)))
        assertEquals(listOf(ByteSpan(0, 30), ByteSpan(40, 50)), merged)
    }

    @Test
    fun resumePointSkipsCompletedPrefixOnly() {
        val segment = ByteSpan(100, 200)
        assertEquals(100L, TransferRanges.resumePoint(segment, emptyList()))
        assertEquals(150L, TransferRanges.resumePoint(segment, listOf(ByteSpan(100, 150))))
        assertEquals(100L, TransferRanges.resumePoint(segment, listOf(ByteSpan(120, 150))))
        assertEquals(200L, TransferRanges.resumePoint(segment, listOf(ByteSpan(0, 500))))
    }

    @Test
    fun coversRequiresContiguousFullRange() {
        assertTrue(TransferRanges.covers(listOf(ByteSpan(0, 50), ByteSpan(50, 100)), 100))
        assertFalse(TransferRanges.covers(listOf(ByteSpan(0, 50), ByteSpan(51, 100)), 100))
        assertFalse(TransferRanges.covers(listOf(ByteSpan(0, 99)), 100))
    }

    @Test
    fun ledgerRoundTripsAndMerges() {
        val dir = Files.createTempDirectory("ranges").toFile()
        try {
            val part = File(dir, "a.bin.fileapex-segpart").apply { writeBytes(ByteArray(100)) }
            RangeLedger.record(part.path, 100, ByteSpan(0, 40))
            RangeLedger.record(part.path, 100, ByteSpan(40, 60))
            RangeLedger.record(part.path, 100, ByteSpan(80, 100))
            assertEquals(listOf(ByteSpan(0, 60), ByteSpan(80, 100)), RangeLedger.read(part.path, 100))
            assertEquals(listOf(ByteSpan(0, 60), ByteSpan(80, 100)), RangeLedger.prepare(part.path, 100))
            assertTrue(part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ledgerPrepareDropsStalePartWhenSizeChanges() {
        val dir = Files.createTempDirectory("ranges").toFile()
        try {
            val part = File(dir, "b.bin.fileapex-segpart").apply { writeBytes(ByteArray(100)) }
            RangeLedger.record(part.path, 100, ByteSpan(0, 100))
            assertEquals(emptyList<ByteSpan>(), RangeLedger.prepare(part.path, 200))
            assertFalse(part.exists())
            assertFalse(File(RangeLedger.ledgerPathFor(part.path)).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ledgerPrepareDropsPartWithoutLedger() {
        val dir = Files.createTempDirectory("ranges").toFile()
        try {
            val part = File(dir, "c.bin.fileapex-segpart").apply { writeBytes(ByteArray(10)) }
            assertEquals(emptyList<ByteSpan>(), RangeLedger.prepare(part.path, 10))
            assertFalse(part.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ledgerPrepareDropsClaimsBeyondPartLength() {
        val dir = Files.createTempDirectory("ranges").toFile()
        try {
            val part = File(dir, "d.bin.fileapex-segpart").apply { writeBytes(ByteArray(50)) }
            RangeLedger.record(part.path, 100, ByteSpan(0, 80))
            assertEquals(emptyList<ByteSpan>(), RangeLedger.prepare(part.path, 100))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun scratchFilesAreHiddenFromTransfers() {
        assertTrue(SocketFileStreamer.isTransferScratchFile("x.mp4.fileapex-segpart"))
        assertTrue(SocketFileStreamer.isTransferScratchFile("x.mp4.fileapex-segpart.fileapex-ranges"))
        assertFalse(SocketFileStreamer.isTransferScratchFile("x.mp4"))
    }
}
