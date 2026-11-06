package com.fileapex.data.seal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainWriteScanTest {
    @Test
    fun aPlainWriteWithNoSealingIsFlagged() {
        assertTrue(PlainWriteScan.writesPlain("fun f(f: File) { f.writeText(\"x\") }"))
        assertTrue(PlainWriteScan.writesPlain("val p = context.getSharedPreferences(\"a\", 0)"))
    }

    @Test
    fun aRealSealedTextCallClearsTheFile() {
        assertFalse(PlainWriteScan.writesPlain("fun f(f: File) { SealedText.writeFile(f, \"p\", \"x\") }"))
        assertFalse(PlainWriteScan.writesPlain("val s = SealedText.seal(\"p\", x); prefs.getSharedPreferences(\"a\", 0)"))
    }

    @Test
    fun aRealSealedStringsUseClearsTheFile() {
        assertFalse(PlainWriteScan.writesPlain("val s = SealedStrings(slots, \"p\"); context.getSharedPreferences(\"a\", 0)"))
    }

    @Test
    fun mentioningTheSealingNamesInACommentDoesNotClearTheFile() {
        assertTrue(PlainWriteScan.writesPlain("// SealedText.seal( would be better\nf.writeText(\"x\")"))
        assertTrue(PlainWriteScan.writesPlain("/* SealedStrings( later */ f.writeText(\"x\")"))
        assertTrue(PlainWriteScan.writesPlain("/** uses SealedText.open( */\nf.appendText(\"x\")"))
    }

    @Test
    fun mentioningTheSealingNamesInAStringDoesNotClearTheFile() {
        assertTrue(PlainWriteScan.writesPlain("val note = \"SealedStrings( and SealedText.seal(\"; f.writeText(note)"))
        assertTrue(PlainWriteScan.writesPlain("val note = \"\"\"SealedText.seal(\"\"\"\nf.writeBytes(b)"))
    }

    @Test
    fun aWriteNamedOnlyInACommentOrStringIsNotAWrite() {
        assertFalse(PlainWriteScan.writesPlain("// f.writeText(x) is banned\nval a = 1"))
        assertFalse(PlainWriteScan.writesPlain("val msg = \"never call getSharedPreferences(\""))
    }

    @Test
    fun aSlashesInAStringDoNotHideTheRestOfTheLine() {
        assertTrue(PlainWriteScan.writesPlain("val u = \"http://x\"; f.writeText(u)"))
        assertFalse(PlainWriteScan.writesPlain("val u = \"http://x\"; SealedText.seal(\"p\", u); f.writeText(u)"))
    }

    @Test
    fun anEscapedQuoteAndACharLiteralDoNotBreakTheScan() {
        assertTrue(PlainWriteScan.writesPlain("val q = \"a\\\"b\"; val c = '\"'; f.writeText(q)"))
    }
}
