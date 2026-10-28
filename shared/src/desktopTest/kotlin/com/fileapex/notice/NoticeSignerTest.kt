package com.fileapex.notice

import com.fileapex.notice.tool.NoticeSigner
import com.fileapex.notice.tool.NoticeSignerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

class NoticeSignerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val today = NoticeDate.parseEpochDay("2026-10-28")!!

    private fun notices(vararg items: String) = Json.parseToJsonElement("[${items.joinToString(",")}]") as JsonArray

    private fun validate(vararg items: String, issued: String = "2026-10-28") =
        NoticeSigner.validate(NoticeSigner.buildPayload(notices(*items), 1, issued), today)

    private fun expectRefusal(vararg items: String, issued: String = "2026-10-28"): String {
        try {
            validate(*items, issued = issued)
        } catch (e: NoticeSignerException) {
            return e.message.orEmpty()
        }
        fail("expected the signer to refuse")
        return ""
    }

    private fun key(): Path {
        val seed = ByteArray(32) { (it * 7 + 1).toByte() }
        return tmp.newFile("primary.key").toPath().also {
            Files.writeString(it, Base64.getEncoder().encodeToString(seed))
            Files.setPosixFilePermissions(it, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }

    private fun src(text: String): Path = tmp.newFile().toPath().also { Files.writeString(it, text) }

    @Test
    fun aGoodFileValidates() {
        assertEquals(1, validate(NoticeSamples.notice()).notices.size)
    }

    @Test
    fun everythingTheAppWouldSilentlyChangeIsRefused() {
        expectRefusal(NoticeSamples.notice(id = "bad id"))
        expectRefusal(NoticeSamples.notice(extra = ""","platforms":["plan9"]"""))
        expectRefusal(NoticeSamples.notice(extra = ""","requires":["google_link","google_link"]"""))
        expectRefusal(NoticeSamples.notice(extra = ""","link":"https://evil.example/x""""))
        expectRefusal(NoticeSamples.notice(extra = ""","link_play":"https://github.com/byhowiecreations/FileApex""""))
        expectRefusal(NoticeSamples.notice(extra = ""","colour":"red""""))
        expectRefusal(NoticeSamples.notice(extra = ""","action":"reboot""""))
        expectRefusal(NoticeSamples.notice(extra = ""","from":"0.20.0","below":"0.19.0""""))
        expectRefusal(NoticeSamples.notice(expires = "2026-10-28"))
        expectRefusal(NoticeSamples.notice(expires = "2028-01-01"))
        expectRefusal(NoticeSamples.notice(id = "a"), NoticeSamples.notice(id = "a"))
        expectRefusal(NoticeSamples.notice(), issued = "2026-10-29")
        expectRefusal(
            """{"id":"long","severity":"info","expires":"2027-03-01","title":{"en":"${"t".repeat(81)}"},"text":{"en":"x"}}"""
        )
        expectRefusal("""{"id":"ctl","severity":"info","expires":"2027-03-01","title":{"en":"a\u202Eb"},"text":{"en":"x"}}""")
        expectRefusal("""{"id":"noen","severity":"info","expires":"2027-03-01","title":{"es":"t"},"text":{"es":"x"}}""")
    }

    @Test
    fun signingWritesAFileTheAppAcceptsAndSerialsRise() {
        val keyFile = key()
        val seed = NoticeSigner.readSeed(keyFile)
        val out = tmp.newFolder().toPath().resolve("notice/notice.json")
        val source = src("[" + NoticeSamples.notice().replace("2027-03-01", "2027-03-01") + "]")
        NoticeSigner.signFile(source, out, keyFile, "2026-10-28", today)
        val first = NoticeFileReader.read(Files.readAllBytes(out), listOf(NoticeSigner.publicKey(seed)), 0L) as NoticeReadResult.Accepted
        assertEquals(1L, first.payload.serial)
        NoticeSigner.signFile(source, out, keyFile, "2026-10-28", today)
        val second = NoticeFileReader.read(Files.readAllBytes(out), listOf(NoticeSigner.publicKey(seed)), 1L) as NoticeReadResult.Accepted
        assertEquals(2L, second.payload.serial)
    }

    @Test
    fun aFileSignedWithAnotherKeyIsNotAcceptedByTheTrustedKey() {
        val keyFile = key()
        val out = tmp.newFolder().toPath().resolve("n.json")
        NoticeSigner.signFile(src("[]"), out, keyFile, "2026-10-28", today)
        val other = NoticeTestKey().publicKey
        assertEquals(NoticeReadResult.BadSignature, NoticeFileReader.read(Files.readAllBytes(out), listOf(other), 0L))
    }

    @Test
    fun anEmptyListSignsSoEveryNoticeCanBeRetracted() {
        val keyFile = key()
        val out = tmp.newFolder().toPath().resolve("n.json")
        NoticeSigner.signFile(src("[]"), out, keyFile, "2026-10-28", today)
        val read = NoticeFileReader.read(Files.readAllBytes(out), listOf(NoticeSigner.publicKey(NoticeSigner.readSeed(keyFile))), 0L)
        assertTrue((read as NoticeReadResult.Accepted).payload.notices.isEmpty())
    }

    @Test
    fun aDamagedExistingFileIsNeverOverwritten() {
        val out = tmp.newFile("n.json").toPath().also { Files.writeString(it, "oops") }
        try {
            NoticeSigner.signFile(src("[]"), out, key(), "2026-10-28", today)
            fail("expected a refusal")
        } catch (e: NoticeSignerException) {
            assertEquals("oops", Files.readString(out))
        }
    }

    @Test
    fun aKeyReadableByOthersIsRefused() {
        val keyFile = key()
        Files.setPosixFilePermissions(keyFile, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ))
        try {
            NoticeSigner.readSeed(keyFile)
            fail("expected a refusal")
        } catch (e: NoticeSignerException) {
            assertTrue(e.message!!.contains("chmod 600"))
        }
    }

    @Test
    fun keysAreCreatedOutsideTheProjectOnly() {
        val repo = tmp.newFolder("repo").toPath()
        try {
            NoticeSigner.generateKeys(repo.resolve("keys"), repo)
            fail("expected a refusal")
        } catch (e: NoticeSignerException) {
            assertFalse(Files.exists(repo.resolve("keys/primary.key")))
        }
        val outside = tmp.newFolder("outside").toPath().resolve("keys")
        val printed = NoticeSigner.generateKeys(outside, repo)
        assertEquals(2, printed.size)
        assertTrue(Files.exists(outside.resolve("primary.key")) && Files.exists(outside.resolve("backup.key")))
        assertEquals(32, Base64.getDecoder().decode(printed[0].substringAfter("public: ")).size)
        try {
            NoticeSigner.generateKeys(outside, repo)
            fail("expected a refusal to overwrite")
        } catch (e: NoticeSignerException) {
            assertTrue(e.message!!.contains("already exists"))
        }
    }
}
