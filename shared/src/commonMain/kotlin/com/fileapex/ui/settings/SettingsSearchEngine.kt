package com.fileapex.ui.settings

import com.fileapex.i18n.AppI18n
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.ADVANCED_IDS
import com.fileapex.ui.SettingsMenuEntry
import com.fileapex.ui.SettingsMenuIds
import com.fileapex.ui.settingsMenuEntries
import kotlin.math.abs

object SettingsSearchIds {
    const val ABOUT = "about"
}

data class SettingsSearchOptions(
    val playStoreBuild: Boolean = false,
    val desktopFileSelection: Boolean = false,
    val deviceNamePage: Boolean = true,
    val windowsFluent: Boolean = false,
    val backupSync: Boolean = false,
    val googleCloud: Boolean = true,
    val tailscale: Boolean = true,
    val iroh: Boolean = false,
    val groups: Boolean = false,
    val shareLink: Boolean = false,
    val autoLaunchOnDesktop: Boolean = false,
    val fullDiskAccess: Boolean = false,
    val showAdvancedSettings: Boolean = true,
    val inUseAdvancedIds: Set<String> = emptySet(),
    val navPosition: Boolean = true
)

data class SettingsSearchResult(
    val id: String,
    val entry: SettingsMenuEntry?,
    val matchedHint: String?,
    val isAdvanced: Boolean,
    val score: Int
)

private data class SubFeatureIndex(
    val labelKey: String,
    val fallbackLabel: String,
    val keywords: List<String>
) {
    fun localizedLabel(): String = AppI18n.t(labelKey).takeIf { it.isNotBlank() && it != labelKey } ?: fallbackLabel
}

private data class SearchEntrySpec(
    val titleKey: String,
    val fallbackTitle: String,
    val docKeywords: List<String>,
    val subFeatures: List<SubFeatureIndex>
)

object SettingsSearchEngine {

    private val stopWords = setOf(
        "how", "do", "i", "can", "could", "would", "should", "to", "the", "a", "an",
        "my", "on", "in", "at", "of", "for", "with", "and", "or", "is", "are", "be",
        "am", "running", "where", "when", "what", "why", "want", "need", "get", "make",
        "let", "me", "turn", "set", "change", "configure", "switch", "put", "toggle",
        "use", "open", "app", "fileapex", "settings", "setting", "option", "options",
        "page", "menu", "into", "onto", "from", "all", "paired", "como", "donde", "que",
        "mi", "en", "de", "para", "los", "las", "un", "una"
    )

    private val synonymGroups: List<Set<String>> = listOf(
        setOf("ip", "address", "hide", "private", "privacy", "relay", "tunnel", "vpn", "mesh", "remote"),
        setOf("alert", "alerts", "notification", "notifications", "notify", "popup", "sound", "ring", "badge", "mute", "off", "critical"),
        setOf("backup", "sync", "photo", "photos", "camera", "dcim", "picture", "pictures", "screenshot", "screenshots", "gallery", "media", "auto", "save", "wifi", "cellular"),
        setOf("clipboard", "copy", "paste", "image", "images", "screenshot", "screenshots", "text"),
        setOf("theme", "themes", "dark", "light", "mode", "color", "colors", "style", "kinetic", "simple", "flux", "clean", "glass", "appearance"),
        setOf("boot", "boots", "startup", "signin", "login", "autostart", "autolaunch", "reboot", "launch"),
        setOf("pin", "passcode", "password", "lock", "timeout", "idle", "biometric", "security"),
        setOf("wipe", "erase", "reset", "leave", "cluster", "disconnect", "unpair", "remove", "devices"),
        setOf("nav", "navigation", "bar", "sidebar", "rail", "bottom", "left", "right", "dock", "layout", "position"),
        setOf("version", "about", "license", "licenses", "source", "credit", "credits", "build"),
        setOf("mica", "acrylic", "fluent", "backdrop", "blur"),
        setOf("disk", "permission", "permissions", "folder", "macos", "full"),
        setOf("update", "updates", "upgrade", "release", "github"),
        setOf("bulletin", "board", "notes", "chat", "bubbles", "messages", "timestamps"),
        setOf("bug", "issue", "report", "feedback", "email", "support", "help"),
        setOf("account", "google", "drive", "sign", "unlink", "cloud"),
        setOf("battery", "doze", "background", "keep", "alive", "watchdog", "sleep", "persistence"),
        setOf("specs", "cpu", "gpu", "ram", "hardware", "telemetry", "details", "stats"),
        setOf("name", "rename", "hostname", "device"),
        setOf("language", "locale", "espanol", "chinese", "translate")
    )

    private val wordToSynonyms: Map<String, Set<String>> by lazy {
        val map = HashMap<String, MutableSet<String>>()
        for (group in synonymGroups) {
            for (word in group) {
                map.getOrPut(word) { LinkedHashSet() }.addAll(group)
            }
        }
        map
    }

    private val specs: Map<String, SearchEntrySpec> = mapOf(
        SettingsMenuIds.DEVICE_NAME to SearchEntrySpec(
            titleKey = "device_name",
            fallbackTitle = "Device Name",
            docKeywords = listOf("device", "name", "rename", "hostname", "broadcast", "label"),
            subFeatures = listOf(
                SubFeatureIndex("device_name", "Broadcast name", listOf("rename", "visible", "other", "devices"))
            )
        ),
        SettingsMenuIds.LANGUAGE to SearchEntrySpec(
            titleKey = "language",
            fallbackTitle = "Language",
            docKeywords = listOf("language", "locale", "english", "spanish", "espanol", "chinese", "translate"),
            subFeatures = listOf(
                SubFeatureIndex("language", "Display language", listOf("locale", "translation", "region"))
            )
        ),
        SettingsMenuIds.CHECK_FOR_UPDATES to SearchEntrySpec(
            titleKey = "check_for_updates",
            fallbackTitle = "Check for Updates",
            docKeywords = listOf("update", "updates", "upgrade", "version", "github", "release", "interval"),
            subFeatures = listOf(
                SubFeatureIndex("check_for_updates", "Automatic update checks", listOf("github", "release", "frequency"))
            )
        ),
        SettingsMenuIds.BACKUP_SYNC to SearchEntrySpec(
            titleKey = "backup_sync",
            fallbackTitle = "Backup & Sync",
            docKeywords = listOf("backup", "sync", "auto", "save", "computer", "photos", "camera", "dcim", "screenshots", "pictures", "wifi", "cellular", "samsung", "pixel"),
            subFeatures = listOf(
                SubFeatureIndex("backup_preset_photos", "Photos preset", listOf("photos", "camera", "dcim", "pictures", "gallery")),
                SubFeatureIndex("backup_preset_screenshots", "Screenshots preset", listOf("screenshots", "screenshot", "save", "computer")),
                SubFeatureIndex("backup_wifi_only", "Wi-Fi only", listOf("wifi", "cellular", "metered", "network"))
            )
        ),
        SettingsMenuIds.REPORT_ISSUE to SearchEntrySpec(
            titleKey = "report_issue_feedback",
            fallbackTitle = "Report an Issue",
            docKeywords = listOf("report", "issue", "bug", "feedback", "email", "support", "help", "contact"),
            subFeatures = listOf(
                SubFeatureIndex("feedback_option_email", "Email support", listOf("bug", "contact", "feedback"))
            )
        ),
        SettingsMenuIds.BACKGROUND_PERSISTENCE to SearchEntrySpec(
            titleKey = "background_persistence",
            fallbackTitle = "Background Persistence",
            docKeywords = listOf("background", "battery", "optimization", "doze", "watchdog", "alive", "service", "persistence", "sleep"),
            subFeatures = listOf(
                SubFeatureIndex("background_persistence", "Unrestricted background service", listOf("battery", "doze", "watchdog", "kill", "sleep"))
            )
        ),
        SettingsMenuIds.AUTO_LAUNCH to SearchEntrySpec(
            titleKey = "auto_launch_on_reboot",
            fallbackTitle = "Auto-Launch",
            docKeywords = listOf("auto", "launch", "autostart", "startup", "boot", "boots", "reboot", "signin", "login", "windows"),
            subFeatures = listOf(
                SubFeatureIndex("auto_launch_on_signin", "Start at sign-in or boot", listOf("boot", "boots", "startup", "windows", "login", "signin"))
            )
        ),
        SettingsMenuIds.TAILSCALE to SearchEntrySpec(
            titleKey = "tailscale_overlay",
            fallbackTitle = "Tailscale",
            docKeywords = listOf("tailscale", "vpn", "overlay", "mesh", "remote", "hide", "ip", "private", "tailnet"),
            subFeatures = listOf(
                SubFeatureIndex("tailscale_header", "Private overlay network", listOf("hide", "ip", "remote", "mesh", "vpn", "tailnet"))
            )
        ),
        SettingsMenuIds.IROH to SearchEntrySpec(
            titleKey = "iroh_title",
            fallbackTitle = "Iroh",
            docKeywords = listOf("iroh", "p2p", "relay", "hide", "ip", "remote", "key", "nat", "rest"),
            subFeatures = listOf(
                SubFeatureIndex("iroh_relay_title", "Relay routing", listOf("hide", "ip", "relay", "remote", "internet"))
            )
        ),
        SettingsMenuIds.DRIVE_RELAY to SearchEntrySpec(
            titleKey = "drive_relay",
            fallbackTitle = "Drive Relay",
            docKeywords = listOf("drive", "relay", "google", "hide", "ip", "private", "privacy", "tunnel", "remote"),
            subFeatures = listOf(
                SubFeatureIndex("google_drive_relay", "Relay transfers without exposing IP", listOf("hide", "ip", "privacy", "google", "drive", "relay"))
            )
        ),
        SettingsMenuIds.THEMES to SearchEntrySpec(
            titleKey = "themes",
            fallbackTitle = "Themes",
            docKeywords = listOf("theme", "themes", "dark", "light", "mode", "kinetic", "simple", "flux", "clean", "glass", "carousel", "appearance", "colors"),
            subFeatures = listOf(
                SubFeatureIndex("themes", "App theme and dark/light look", listOf("dark", "light", "mode", "kinetic", "simple", "flux", "clean", "jaded"))
            )
        ),
        SettingsMenuIds.BULLETIN_BOARD to SearchEntrySpec(
            titleKey = "bulletin_board",
            fallbackTitle = "Bulletin Board",
            docKeywords = listOf("bulletin", "board", "notes", "messages", "chat", "bubbles", "timestamps", "style"),
            subFeatures = listOf(
                SubFeatureIndex("bulletin_board", "Notes bubbles and style", listOf("chat", "bubbles", "timestamps", "board", "notes"))
            )
        ),
        SettingsMenuIds.NOTIFICATIONS to SearchEntrySpec(
            titleKey = "notifications",
            fallbackTitle = "Notifications",
            docKeywords = listOf("notification", "notifications", "alert", "alerts", "critical", "off", "mute", "popup", "sound", "ring", "capsule"),
            subFeatures = listOf(
                SubFeatureIndex("critical_notifications_title", "Critical notifications", listOf("critical", "alert", "alerts", "off", "mute", "popup")),
                SubFeatureIndex("notifications", "Transfer and board alerts", listOf("notes", "transfer", "capsule", "off", "mute"))
            )
        ),
        SettingsMenuIds.CLIPBOARD to SearchEntrySpec(
            titleKey = "clipboard",
            fallbackTitle = "Clipboard",
            docKeywords = listOf("clipboard", "copy", "paste", "image", "images", "screenshot", "screenshots", "sync", "save", "computer"),
            subFeatures = listOf(
                SubFeatureIndex("clipboard_images_sync", "Sync clipboard images", listOf("image", "images", "screenshot", "screenshots", "copy", "paste")),
                SubFeatureIndex("clipboard", "Auto share clipboard target", listOf("copy", "paste", "device", "save"))
            )
        ),
        SettingsMenuIds.GROUPS to SearchEntrySpec(
            titleKey = "groups_title",
            fallbackTitle = "Groups",
            docKeywords = listOf("groups", "friends", "contacts", "share", "circles"),
            subFeatures = listOf(
                SubFeatureIndex("groups_title", "Friends and groups", listOf("friends", "contacts", "groups"))
            )
        ),
        SettingsMenuIds.DEVICE_DETAILS to SearchEntrySpec(
            titleKey = "device_details",
            fallbackTitle = "Device Details & Stats",
            docKeywords = listOf("device", "details", "stats", "specs", "cpu", "gpu", "ram", "memory", "hardware", "telemetry"),
            subFeatures = listOf(
                SubFeatureIndex("peer_telemetry_fields", "Hardware stats visibility", listOf("cpu", "gpu", "ram", "specs", "telemetry"))
            )
        ),
        SettingsMenuIds.DESKTOP_LAYOUT to SearchEntrySpec(
            titleKey = "nav_position_title",
            fallbackTitle = "Navigation Bar Position",
            docKeywords = listOf("nav", "navigation", "bar", "sidebar", "rail", "bottom", "left", "right", "dock", "layout", "desktop"),
            subFeatures = listOf(
                SubFeatureIndex("nav_position_title", "Left, bottom, or right dock", listOf("rail", "sidebar", "bottom", "left", "right", "bar"))
            )
        ),
        SettingsMenuIds.WINDOWS_DESIGN to SearchEntrySpec(
            titleKey = "windows_design",
            fallbackTitle = "Windows Design",
            docKeywords = listOf("windows", "design", "mica", "acrylic", "fluent", "backdrop", "blur"),
            subFeatures = listOf(
                SubFeatureIndex("windows_design", "Mica and Acrylic backdrop", listOf("mica", "acrylic", "fluent", "blur", "backdrop"))
            )
        ),
        SettingsMenuIds.PIN_REQUIRED to SearchEntrySpec(
            titleKey = "pin_required",
            fallbackTitle = "PIN Lock",
            docKeywords = listOf("pin", "passcode", "password", "lock", "idle", "timeout", "biometric", "security", "unlock"),
            subFeatures = listOf(
                SubFeatureIndex("pin_required", "Lock PIN and idle timeout", listOf("passcode", "password", "lock", "idle", "timeout", "biometric"))
            )
        ),
        SettingsMenuIds.GOOGLE_ACCOUNT to SearchEntrySpec(
            titleKey = "google_account",
            fallbackTitle = "Google Account",
            docKeywords = listOf("google", "account", "sign", "signin", "unlink", "cloud", "drive"),
            subFeatures = listOf(
                SubFeatureIndex("google_account", "Sign in or unlink Google account", listOf("google", "cloud", "unlink", "signin"))
            )
        ),
        SettingsMenuIds.SHARE_LINK to SearchEntrySpec(
            titleKey = "share_link_page_title",
            fallbackTitle = "Share as a Link",
            docKeywords = listOf("share", "link", "browser", "relay", "public", "hide", "ip", "drop", "url"),
            subFeatures = listOf(
                SubFeatureIndex("share_link_page_title", "Browser relay link settings", listOf("link", "browser", "relay", "hide", "ip"))
            )
        ),
        SettingsMenuIds.FULL_DISK_ACCESS to SearchEntrySpec(
            titleKey = "full_disk_access",
            fallbackTitle = "Full Disk Access",
            docKeywords = listOf("full", "disk", "access", "macos", "permission", "permissions", "folder"),
            subFeatures = listOf(
                SubFeatureIndex("full_disk_access", "macOS folder access permission", listOf("macos", "permission", "disk", "folders"))
            )
        ),
        SettingsMenuIds.LEAVE_CLUSTER to SearchEntrySpec(
            titleKey = "leave_cluster_wipe_title",
            fallbackTitle = "Leave Cluster",
            docKeywords = listOf("leave", "cluster", "wipe", "erase", "reset", "unpair", "disconnect", "remove", "devices", "keys"),
            subFeatures = listOf(
                SubFeatureIndex("leave_cluster_wipe_title", "Wipe identity and unpair cluster", listOf("erase", "wipe", "reset", "unpair", "remove", "disconnect"))
            )
        ),
        SettingsSearchIds.ABOUT to SearchEntrySpec(
            titleKey = "about_title",
            fallbackTitle = "About",
            docKeywords = listOf("about", "version", "license", "licenses", "open", "source", "legal", "credits", "tailscale", "iroh"),
            subFeatures = listOf(
                SubFeatureIndex("about_title", "Version and open source licenses", listOf("version", "licenses", "open", "source", "credits"))
            )
        )
    )

    fun search(
        query: String,
        state: SettingsUiState,
        options: SettingsSearchOptions
    ): List<SettingsSearchResult> {
        val rawTokens = tokenize(query)
        if (rawTokens.isEmpty()) return emptyList()
        val meaningful = rawTokens.filterNot { it in stopWords }.ifEmpty { rawTokens }

        val entries = settingsMenuEntries(
            playStoreBuild = options.playStoreBuild,
            desktopFileSelection = options.desktopFileSelection,
            deviceNamePage = options.deviceNamePage,
            windowsFluent = options.windowsFluent,
            backupSync = options.backupSync,
            googleCloud = options.googleCloud,
            tailscale = options.tailscale,
            iroh = options.iroh,
            groups = options.groups,
            shareLink = options.shareLink,
            autoLaunchOnDesktop = options.autoLaunchOnDesktop,
            fullDiskAccess = options.fullDiskAccess,
            showAdvanced = true,
            inUseAdvancedIds = options.inUseAdvancedIds,
            navPosition = options.navPosition
        )

        val candidates = entries.map { it.id to (it as SettingsMenuEntry?) } + (SettingsSearchIds.ABOUT to null)
        return candidates.mapNotNull { (id, entry) ->
            scoreEntry(id, entry, meaningful, state, options)
        }.sortedWith(compareByDescending<SettingsSearchResult> { it.score }.thenBy { it.id })
    }

    private fun scoreEntry(
        id: String,
        entry: SettingsMenuEntry?,
        queryTokens: List<String>,
        state: SettingsUiState,
        options: SettingsSearchOptions
    ): SettingsSearchResult? {
        val spec = specs[id] ?: return null
        val localizedTitle = AppI18n.t(spec.titleKey).takeIf { it.isNotBlank() && it != spec.titleKey } ?: spec.fallbackTitle
        val titleTokens = tokenize(localizedTitle)
        val liveExtraTokens = liveTokensFor(id, state)
        val directDocTokens = (titleTokens + spec.docKeywords + liveExtraTokens).toSet()

        var score = 0
        var matchedCount = 0
        var bestSubFeatureScore = 0
        var bestSubFeatureLabel: String? = null

        val joinedTitle = titleTokens.joinToString(" ")
        val joinedQuery = queryTokens.joinToString(" ")
        if (joinedQuery.isNotBlank() && joinedTitle.contains(joinedQuery)) {
            score += 90
        }

        for (q in queryTokens) {
            val tokenMatch = scoreTokenAgainstSet(q, titleTokens, directDocTokens)
            if (tokenMatch > 0) {
                matchedCount += 1
                score += tokenMatch
            }
            for (sf in spec.subFeatures) {
                val sfLabel = sf.localizedLabel()
                val sfTokens = (tokenize(sfLabel) + sf.keywords).toSet()
                val sfScore = scoreTokenAgainstSet(q, tokenize(sfLabel), sfTokens)
                if (sfScore > 0) {
                    val boosted = sfScore + sf.keywords.count { it == q } * 10
                    if (boosted > bestSubFeatureScore) {
                        bestSubFeatureScore = boosted
                        bestSubFeatureLabel = sfLabel
                    }
                }
            }
        }

        val requiredMatches = if (queryTokens.size <= 2) queryTokens.size else (queryTokens.size - 1).coerceAtLeast(2)
        if (matchedCount < requiredMatches || score <= 0) return null

        score += matchedCount * 35
        if (matchedCount == queryTokens.size) score += 25

        val isHiddenAdvanced = id in ADVANCED_IDS &&
            !options.showAdvancedSettings &&
            id !in options.inUseAdvancedIds

        return SettingsSearchResult(
            id = id,
            entry = entry,
            matchedHint = bestSubFeatureLabel,
            isAdvanced = isHiddenAdvanced,
            score = score
        )
    }

    private fun scoreTokenAgainstSet(
        token: String,
        titleTokens: List<String>,
        docTokens: Set<String>
    ): Int {
        if (token in titleTokens) return 55
        if (titleTokens.any { it.startsWith(token) || (token.length >= 4 && token.startsWith(it)) }) return 40
        if (token in docTokens) return 32
        if (docTokens.any { it.startsWith(token) && token.length >= 3 }) return 24

        val synonyms = wordToSynonyms[token].orEmpty()
        if (synonyms.isNotEmpty()) {
            if (synonyms.any { it in titleTokens }) return 24
            if (synonyms.any { it in docTokens }) return 18
        }

        if (token.length >= 4) {
            if (docTokens.any { candidate -> candidate.length >= 4 && withinOneEdit(token, candidate) }) {
                return 16
            }
        }
        return 0
    }

    private fun liveTokensFor(id: String, state: SettingsUiState): List<String> = when (id) {
        SettingsMenuIds.DEVICE_NAME -> tokenize(state.deviceNameBroadcasting)
        SettingsMenuIds.THEMES -> tokenize(state.appTheme.name) + tokenize(state.kineticStyle.name)
        SettingsMenuIds.LANGUAGE -> tokenize(AppI18n.languageRowLabel(AppI18n.locale)) + tokenize(AppI18n.locale.name)
        else -> emptyList()
    }

    internal fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.lowercase()
            .replace("'", "")
            .replace("\u2019", "")
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (ch in normalized) {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                out.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
