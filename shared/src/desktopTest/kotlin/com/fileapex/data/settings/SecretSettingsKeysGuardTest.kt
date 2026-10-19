package com.fileapex.data.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A settings key that looks like a secret (a key, token, PIN or password) must be in BaseAppSettings.SECRET_KEYS so it
 * is sealed at rest, or be on the short list of look-alikes that hold nothing secret. A new secret cannot be added in clear text by accident.
 */
class SecretSettingsKeysGuardTest {
    private val notSecret = setOf(
        "KEY_PIN_REQUIRED", "KEY_PIN_IDLE_TIMEOUT", "KEY_TAILSCALE_AUTH_KEY_FINGERPRINT", "KEY_GOOGLE_BACKUP_EMAIL_HINT",
        "KEY_CLIPBOARD_SHARING", "KEY_CLIPBOARD_SHARE_MODE", "KEY_CLIPBOARD_TARGET_DEVICES", "KEY_CLIPBOARD_TARGET_CONFIGURED",
        "KEY_CLIPBOARD_OPT_IN_PROMPT_SHOWN", "KEY_CLIPBOARD_VIA_CELLULAR", "KEY_CLIPBOARD_ACCESSIBILITY",
        "KEY_CLIPBOARD_SEND_NOTIFICATION", "KEY_CLIPBOARD_SHIZUKU", "KEY_CLIPBOARD_AUTO_SEND", "KEY_CLIPBOARD_DISCLOSURE_ACK",
        "KEY_NOTIFICATION_BROADCAST_CODES", "KEY_FREESTYLE_CARD_PINNED_ACTIONS", "KEY_FREESTYLE_CARD_VERTICAL_PINNED_ACTIONS",
        "KEY_FREESTYLE_TILE_PINNED_ACTIONS", "KEY_IROH_PEERS", "KEY_IROH_ENABLED", "KEY_IROH_ADVANCED_DEBUG",
        "KEY_IROH_REST_WHEN_IDLE", "KEY_IROH_RELAY_ENABLED", "KEY_IROH_RELAY_URL", "KEY_TAILSCALE_ENABLED",
        "KEY_TAILSCALE_SETUP_EXPANDED", "KEY_GOOGLE", "KEY_GOOGLE_EMAIL", "KEY_GOOGLE_UID", "KEY_GOOGLE_RESTORE_PENDING"
    )
    private val looksSecret = Regex("PRIVATE|SECRET|TOKEN|PASSWORD|DEVICE_PIN|AUTH_KEY|_KEY\\b")

    @Test
    fun everySecretLookingKeyIsSealed() {
        val source = File("src/commonMain/kotlin/com/fileapex/data/settings/BaseAppSettings.kt").readText()
        val declared = Regex("const val (KEY_[A-Z0-9_]+) = \"([^\"]+)\"").findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        val sealedValues = BaseAppSettings.SECRET_KEYS
        val unsealed = declared.filter { (name, _) -> looksSecret.containsMatchIn(name) && name !in notSecret }
            .filterValues { it !in sealedValues }.keys.sorted()
        assertEquals(emptyList<String>(), unsealed)
    }

    @Test
    fun theSealedListNamesRealKeys() {
        val source = File("src/commonMain/kotlin/com/fileapex/data/settings/BaseAppSettings.kt").readText()
        val values = Regex("const val KEY_[A-Z0-9_]+ = \"([^\"]+)\"").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(emptyList<String>(), BaseAppSettings.SECRET_KEYS.filter { it !in values }.sorted())
    }
}
