package com.fileapex.notice.tool

import com.fileapex.notice.NoticeDate
import com.fileapex.notice.NoticeEnvelopeParser
import com.fileapex.notice.NoticeFileReader
import com.fileapex.notice.NoticePayload
import com.fileapex.notice.NoticePayloadParser
import com.fileapex.notice.NoticePayloadResult
import com.fileapex.notice.NoticeReadResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64

class NoticeSignerException(message: String) : Exception(message)

/** Builds, checks and signs `notice.json`. Run through `docs/notice-tools/sign_notice.sh`; the private key never lives in the repo. */
object NoticeSigner {
    private val ALLOWED_KEYS = setOf(
        "id", "severity", "from", "to", "below", "expires", "platforms", "channels", "requires", "title", "text", "link", "link_play", "action"
    )

    /** Compact payload text; this exact string is what gets signed and stored in the file. */
    fun buildPayload(notices: JsonArray, serial: Long, issued: String): String =
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(NoticePayloadParser.FORMAT_VERSION),
                "serial" to JsonPrimitive(serial),
                "issued" to JsonPrimitive(issued),
                "notices" to notices
            )
        ).toString()

    /** Stricter than the app: anything the app would silently drop or cut is an error here. */
    fun validate(payload: String, todayEpochDay: Long): NoticePayload {
        val parsed = (NoticePayloadParser.parse(payload) as? NoticePayloadResult.Ok)?.payload
            ?: throw NoticeSignerException("The payload is not a valid notice payload.")
        if (parsed.issuedEpochDay > todayEpochDay) throw NoticeSignerException("issued is after today.")
        val raw = (Json.parseToJsonElement(payload) as JsonObject)["notices"] as JsonArray
        if (raw.size != parsed.notices.size) {
            throw NoticeSignerException("${raw.size - parsed.notices.size} notice(s) would be dropped by the app: check id, severity, versions, expires (at most 12 months after issued), and that title and text have an \"en\" entry.")
        }
        raw.forEach { checkNotice(it as JsonObject, parsed, todayEpochDay) }
        return parsed
    }

    private fun checkNotice(o: JsonObject, parsed: NoticePayload, todayEpochDay: Long) {
        val id = (o["id"] as JsonPrimitive).content
        val notice = parsed.notices.firstOrNull { it.id == id } ?: throw NoticeSignerException("Notice $id is duplicated or invalid.")
        (o.keys - ALLOWED_KEYS).firstOrNull()?.let { throw NoticeSignerException("Notice $id has an unknown field \"$it\".") }
        if (notice.expiresEpochDay <= todayEpochDay) throw NoticeSignerException("Notice $id has already expired.")
        val from = notice.from
        val top = notice.to ?: notice.below
        if (from != null && top != null && from > top) throw NoticeSignerException("Notice $id has from above to/below, so it never matches.")
        listOf("platforms", "channels", "requires").forEach { key ->
            val list = o[key] as? JsonArray ?: return@forEach
            val applied = when (key) {
                "platforms" -> notice.platforms?.size
                "channels" -> notice.channels?.size
                else -> notice.requires?.size
            }
            if (list.size != applied) throw NoticeSignerException("Notice $id has an unknown or repeated value in \"$key\".")
        }
        mapOf("title" to notice.title, "text" to notice.text).forEach { (key, cleaned) ->
            val entries = o[key] as JsonObject
            if (entries.size != cleaned.size) throw NoticeSignerException("Notice $id has an invalid language code in \"$key\".")
            entries.forEach { (language, value) ->
                if (cleaned[language] != (value as JsonPrimitive).content) {
                    throw NoticeSignerException("Notice $id \"$key\" ($language) has control characters, is empty, or is too long (title 80, text 600).")
                }
            }
        }
        (o["action"] as? JsonPrimitive)?.let { if (it.content != "update") throw NoticeSignerException("Notice $id has an unknown action \"${it.content}\". Only \"update\" exists.") }
        (o["link"] as? JsonPrimitive)?.let { if (it.content != notice.link) throw NoticeSignerException("Notice $id link is not allowed.") }
        (o["link_play"] as? JsonPrimitive)?.let { if (it.content != notice.linkPlay) throw NoticeSignerException("Notice $id link_play is not allowed.") }
    }

    fun sign(payload: String, seed: ByteArray): ByteArray {
        val bytes = payload.encodeToByteArray()
        return Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(seed, 0))
            update(bytes, 0, bytes.size)
            generateSignature()
        }
    }

    fun publicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun envelopeText(payload: String, signature: ByteArray): String =
        JsonObject(mapOf("payload" to JsonPrimitive(payload), "sig" to JsonPrimitive(Base64.getEncoder().encodeToString(signature)))).toString() + "\n"

    fun previousSerial(outFile: Path): Long {
        if (!Files.exists(outFile)) return 0L
        val envelope = NoticeEnvelopeParser.parse(Files.readAllBytes(outFile))
            ?: throw NoticeSignerException("$outFile exists but is not a valid notice file. Refusing to overwrite it.")
        return (NoticePayloadParser.parse(envelope.payload) as? NoticePayloadResult.Ok)?.payload?.serial
            ?: throw NoticeSignerException("$outFile has an unreadable payload. Refusing to overwrite it.")
    }

    fun readSeed(keyFile: Path): ByteArray {
        requirePrivate(keyFile)
        val seed = Base64.getDecoder().decode(Files.readString(keyFile).trim())
        if (seed.size != 32) throw NoticeSignerException("$keyFile is not a 32-byte Ed25519 seed.")
        return seed
    }

    /** Signs [noticesFile] into [outFile] with the next serial, checks the result with the app's own reader, then replaces the file. */
    fun signFile(noticesFile: Path, outFile: Path, keyFile: Path, issued: String, todayEpochDay: Long): String {
        val notices = Json.parseToJsonElement(Files.readString(noticesFile)) as? JsonArray
            ?: throw NoticeSignerException("$noticesFile must contain a JSON array of notices.")
        val previous = previousSerial(outFile)
        val serial = previous + 1
        val payload = buildPayload(notices, serial, issued)
        validate(payload, todayEpochDay)
        val seed = readSeed(keyFile)
        val file = envelopeText(payload, sign(payload, seed))
        if (file.encodeToByteArray().size > NoticeEnvelopeParser.MAX_FILE_BYTES) throw NoticeSignerException("The signed file is over 32 KB.")
        val check = NoticeFileReader.read(file.encodeToByteArray(), listOf(publicKey(seed)), previous)
        if (check !is NoticeReadResult.Accepted) throw NoticeSignerException("The signed file failed the app's own check: $check.")
        outFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val temp = Files.createTempFile(outFile.toAbsolutePath().parent, "notice", ".tmp")
        Files.writeString(temp, file)
        Files.move(temp, outFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return "Signed ${check.payload.notices.size} notice(s) as serial $serial into $outFile"
    }

    /** Writes a primary and a backup seed into [dir] (outside [repoRoot]) and returns both public keys, base64. */
    fun generateKeys(dir: Path, repoRoot: Path): List<String> {
        if (dir.toAbsolutePath().normalize().startsWith(repoRoot.toAbsolutePath().normalize())) {
            throw NoticeSignerException("Keys must be created outside the project folder.")
        }
        Files.createDirectories(dir)
        return listOf("primary.key", "backup.key").map { name ->
            val target = dir.resolve(name)
            if (Files.exists(target)) throw NoticeSignerException("$target already exists. Refusing to overwrite a key.")
            val pair = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
            val seed = (pair.private as Ed25519PrivateKeyParameters).encoded
            Files.writeString(target, Base64.getEncoder().encodeToString(seed) + "\n")
            runCatching { Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
            "$name public: " + Base64.getEncoder().encodeToString(publicKey(seed))
        }
    }

    private fun requirePrivate(keyFile: Path) {
        if (!Files.exists(keyFile)) throw NoticeSignerException("Key file $keyFile does not exist.")
        val permissions = runCatching { Files.getPosixFilePermissions(keyFile) }.getOrNull() ?: return
        val others = permissions.filter {
            it in setOf(
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE
            )
        }
        if (others.isNotEmpty()) throw NoticeSignerException("$keyFile is readable by other users. Run: chmod 600 $keyFile")
    }

    fun todayEpochDay(): Long = NoticeDate.epochDayOf(System.currentTimeMillis())

    fun todayIso(): String = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
}
