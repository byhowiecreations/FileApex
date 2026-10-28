package com.fileapex.notice.tool

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  generate <dir>                         create primary.key and backup.key (dir must be outside the project)
  sign --notices <file> --key <file> [--out <file>] [--issued YYYY-MM-DD]
                                         sign a JSON array of notices into notice/notice.json with the next serial
  verify --file <file> --public <base64> check a signed file against a public key"""

fun main(args: Array<String>) {
    try {
        println(run(args.toList(), Paths.get("").toAbsolutePath()))
    } catch (e: NoticeSignerException) {
        System.err.println("ERROR: ${e.message}")
        exitProcess(1)
    }
}

private fun run(args: List<String>, cwd: Path): String {
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    return when (args.firstOrNull()) {
        "generate" -> NoticeSigner.generateKeys(
            Paths.get(args.getOrNull(1) ?: throw NoticeSignerException(USAGE)), cwd
        ).joinToString("\n") + "\nPut both public keys in NoticeTrustedKeys. Keep backup.key offline, away from this Mac."
        "sign" -> NoticeSigner.signFile(
            noticesFile = Paths.get(option("--notices") ?: throw NoticeSignerException(USAGE)),
            outFile = Paths.get(option("--out") ?: "notice/notice.json"),
            keyFile = Paths.get(option("--key") ?: throw NoticeSignerException(USAGE)),
            issued = option("--issued") ?: NoticeSigner.todayIso(),
            todayEpochDay = NoticeSigner.todayEpochDay()
        )
        "verify" -> {
            val file = Paths.get(option("--file") ?: throw NoticeSignerException(USAGE))
            val key = java.util.Base64.getDecoder().decode(option("--public") ?: throw NoticeSignerException(USAGE))
            val result = com.fileapex.notice.NoticeFileReader.read(java.nio.file.Files.readAllBytes(file), listOf(key), 0L)
            if (result is com.fileapex.notice.NoticeReadResult.Accepted) "OK: serial ${result.payload.serial}, ${result.payload.notices.size} notice(s)"
            else throw NoticeSignerException("Not accepted: $result")
        }
        else -> throw NoticeSignerException(USAGE)
    }
}
