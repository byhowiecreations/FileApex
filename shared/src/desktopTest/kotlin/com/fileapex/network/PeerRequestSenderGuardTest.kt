package com.fileapex.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every request to a peer names its sender (`from=<deviceId>&mv=<membershipVersion>`, added by withSenderQuery) so the
 * receiver can tell who is asking and which membership version they hold. Raw peer HTTP calls therefore live only in
 * FileApexClient and the peer download, upload and bulletin clients, and the plain GET and POST ones must build their path with withSenderQuery.
 */
class PeerRequestSenderGuardTest {
    private val sourceRoots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin")
        .map { File(it) }.filter { it.exists() }

    private val rawCall = Regex("""\bpeerHttp(Get|Post|GetStreaming|UploadFromFile|UploadFromChannel)\(""")

    @Test
    fun rawPeerHttpCallsLiveOnlyInTheClient() {
        val offenders = sourceRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
                file.name != "FileApexClient.kt" && file.name != "PeerDownloadClient.kt" && file.name != "PeerUploadClient.kt" && file.name != "PeerBulletinClient.kt" && !file.name.startsWith("LanInterfaceBinding") && !file.name.startsWith("DesktopMacTrayBridge") &&
                    file.readLines().any { line ->
                        rawCall.containsMatchIn(line) && !line.contains("fun peerHttp") && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")
                    }
            }.map { it.name }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun plainGetAndPostCallsAddTheSender() {
        val offenders = listOf("FileApexClient.kt", "PeerDownloadClient.kt", "PeerUploadClient.kt", "PeerBulletinClient.kt").flatMap { name ->
        val lines = File("src/commonMain/kotlin/com/fileapex/network/$name").readLines()
        lines.withIndex().filter { (_, line) ->
            Regex("""\bpeerHttp(Get|Post)\(""").containsMatchIn(line) && !line.contains("fun peerHttp")
        }.filter { (index, _) ->
            lines.subList(index, minOf(index + 8, lines.size)).none { it.contains("withSenderQuery") }
        }.map { (index, _) -> "$name:${index + 1}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
