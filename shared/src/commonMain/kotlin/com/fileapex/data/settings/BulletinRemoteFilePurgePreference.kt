package com.fileapex.data.settings

enum class BulletinRemoteFilePurgePreference {
    UNCONFIGURED,
    ENABLED,
    DISABLED;

    companion object {
        fun fromStorage(raw: String?): BulletinRemoteFilePurgePreference {
            return entries.firstOrNull { it.name == raw?.trim() } ?: UNCONFIGURED
        }
    }
}
