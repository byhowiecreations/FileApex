package com.fileapex.cloud.seal

import com.fileapex.cloud.CloudDevicePresence
import com.fileapex.cloud.CloudDeviceRecord
import com.fileapex.data.db.seal.FieldSealer
import java.util.concurrent.ConcurrentHashMap

/**
 * Seals the name, address and port of a cloud device record with the [ClusterRecordKey], so Google holds opaque
 * values. The plain fields are written as well while any device of the cluster runs a build older than
 * [MIN_VERSION_CODE]; once none does they are written empty.
 */
object CloudRecordSeal {
    /** The first build that reads the sealed fields. */
    const val MIN_VERSION_CODE = 221
    const val FIELD_NAME = "sealedName"
    const val FIELD_NET = "sealedNet"
    private const val TABLE = "cloud_device"

    private val versions = ConcurrentHashMap<String, Int>()

    fun noteVersion(deviceId: String, versionCode: Int) { versions[deviceId] = versionCode }

    /** A full registry snapshot: a device missing from it no longer counts, so a dead device cannot keep plain fields on. */
    fun replaceVersions(records: List<CloudDeviceRecord>) {
        val latest = records.associate { it.deviceId to it.clientVersionCode }
        versions.keys.retainAll(latest.keys)
        versions.putAll(latest)
    }

    internal fun forgetVersionsForTests() = versions.clear()

    /** True until every device seen in the cloud runs a build that reads the sealed fields. */
    fun plainStillNeeded(): Boolean = versions.isEmpty() || versions.values.any { it < MIN_VERSION_CODE }

    fun outgoing(record: CloudDeviceRecord): CloudDeviceRecord {
        val key = ClusterRecordKeys.ensure() ?: return record
        val sealed = sealName(key, record.deviceId, record.deviceName) + sealNet(key, record.deviceId, record.lastKnownIp, record.port)
        if (plainStillNeeded()) return record.copy(sealed = sealed)
        return record.copy(deviceName = "", lastKnownIp = "", port = 0, sealed = sealed)
    }

    fun outgoing(presence: CloudDevicePresence): CloudDevicePresence {
        val key = ClusterRecordKeys.ensure() ?: return presence
        val sealed = sealNet(key, presence.deviceId, presence.lastKnownIp, presence.port)
        if (plainStillNeeded()) return presence.copy(sealed = sealed)
        return presence.copy(lastKnownIp = "", port = 0, sealed = sealed)
    }

    /** The plain name to write and the sealed fields that go with it, for an explicit rename. */
    fun forRename(deviceId: String, name: String): Pair<String, Map<String, String>> {
        val key = ClusterRecordKeys.ensure() ?: return name to emptyMap()
        return (if (plainStillNeeded()) name else "") to sealName(key, deviceId, name)
    }

    /** The record with its name, address and port read from the sealed fields when this device holds the key. */
    fun open(record: CloudDeviceRecord): CloudDeviceRecord {
        noteVersion(record.deviceId, record.clientVersionCode)
        val key = ClusterRecordKeys.current() ?: return record
        val sealer = key.sealer()
        val name = record.sealed[FIELD_NAME]?.let { openValue(sealer, key, record.deviceId, "name", it) }
        val net = record.sealed[FIELD_NET]?.let { openValue(sealer, key, record.deviceId, "net", it) }
        val split = net?.lastIndexOf('|') ?: -1
        return record.copy(
            deviceName = name ?: record.deviceName,
            lastKnownIp = if (split > 0) net!!.substring(0, split) else record.lastKnownIp,
            port = if (split > 0) net!!.substring(split + 1).toIntOrNull() ?: record.port else record.port
        )
    }

    private fun sealName(key: ClusterRecordKey, deviceId: String, name: String) =
        mapOf(FIELD_NAME to seal(key, deviceId, "name", name))

    private fun sealNet(key: ClusterRecordKey, deviceId: String, ip: String, port: Int) =
        mapOf(FIELD_NET to seal(key, deviceId, "net", "$ip|$port"))

    private fun seal(key: ClusterRecordKey, deviceId: String, column: String, plain: String): String =
        "${key.id}.${key.sealer().seal(TABLE, "$deviceId.$column", plain)}"

    private fun openValue(sealer: FieldSealer, key: ClusterRecordKey, deviceId: String, column: String, value: String): String? {
        if (!value.startsWith("${key.id}.")) return null
        return sealer.open(TABLE, "$deviceId.$column", value.removePrefix("${key.id}."))
    }
}
