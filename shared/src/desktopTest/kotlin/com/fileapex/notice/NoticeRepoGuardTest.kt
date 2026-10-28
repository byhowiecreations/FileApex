package com.fileapex.notice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class NoticeRepoGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources(vararg dirs: String): List<File> =
        dirs.map { File(root, it) }.filter { it.exists() }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun theTrustedKeysAreNoneOrTwoValidEd25519PublicKeys() {
        val keys = NoticeTrustedKeys.all()
        assertTrue("0 keys (fail closed) or the primary and backup", keys.size == 0 || keys.size == 2)
        keys.forEach { assertEquals(NoticeVerifier.PUBLIC_KEY_BYTES, it.size) }
        assertEquals(keys.size, keys.map { Base64.getEncoder().encodeToString(it) }.toSet().size)
    }

    @Test
    fun noPrivateKeyMaterialLivesInTheNoticeSourcesOrTheNoticeFolder() {
        val files = sources("shared/src/commonMain/kotlin/com/fileapex/notice", "shared/src/jvmCommon/kotlin/com/fileapex/notice") +
            File(root, "notice").walkTopDown().filter { it.isFile }.toList() + File(root, "docs/notice-tools/sign_notice.sh")
        files.filter { it.exists() }.forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} mentions private key material", Regex("BEGIN [A-Z ]*PRIVATE KEY|primary\\.key\\s*=\\s*\"").containsMatchIn(text))
        }
        assertTrue("no key file may sit in the project", root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .none { it.isFile && (it.name == "primary.key" || it.name == "backup.key") })
    }

    @Test
    fun noticeCodeNeverTouchesTheBulletinMessageStore() {
        val forbidden = Regex("BulletinBoardRepository|BulletinBoardSyncEngine|MessageEntity|bulletinBoardRepository|bulletinSyncEngine|outbox", RegexOption.IGNORE_CASE)
        val files = sources("shared/src/commonMain/kotlin/com/fileapex/notice", "shared/src/commonMain/kotlin/com/fileapex/ui/notice")
        assertTrue(files.isNotEmpty())
        files.forEach { assertFalse("${it.name} must stay out of the message store", forbidden.containsMatchIn(it.readText())) }
    }

    @Test
    fun noticeCodeSendsNoIdentifyingHeaderOrParameter() {
        val text = File(root, "shared/src/commonMain/kotlin/com/fileapex/notice/NoticeTransport.kt").readText()
        assertFalse(Regex("UserAgent|currentAppVersionName|deviceId|selfId|header\\((?!HttpHeaders\\.IfNoneMatch)").containsMatchIn(text))
    }

    @Test
    fun theGitattributesProtectTheSignedBytes() {
        val attributes = File(root, ".gitattributes").readText()
        assertTrue(attributes.lines().any { it.trim() == "notice/notice.json -text" })
    }

    @Test
    fun theOfficialNoticeFileIfPresentIsAcceptedByTheBuiltInKeys() {
        val file = File(root, "notice/notice.json")
        val keys = NoticeTrustedKeys.all()
        if (!file.exists() || keys.isEmpty()) return
        val result = NoticeFileReader.read(file.readBytes(), keys, 0L)
        assertTrue("notice/notice.json is not accepted by the built-in keys: $result", result is NoticeReadResult.Accepted)
    }

    @Test
    fun theAppDefaultsToTheOfficialSourceUnlessATestPropertyIsSet() {
        assertEquals("", GeneratedNoticeConfig.NOTICE_URL.takeIf { System.getProperty("fileapex.expectTestNotice") == null }.orEmpty())
    }
}
