package com.fileapex.domain.notifications

object NotificationBroadcastPolicy {
    const val CATEGORY_MESSAGE = "msg"
    const val CATEGORY_EMAIL = "email"

    /** Used when an app does not set a notification category. */
    private val messagingPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "com.motorola.messaging", "com.facebook.orca", "com.discord", "com.Slack",
        "com.microsoft.teams", "com.google.android.apps.dynamite", "com.viber.voip",
        "jp.naver.line.android", "com.tencent.mm", "com.skype.raider", "com.snapchat.android",
        "com.instagram.android", "com.reddit.frontpage"
    )

    private val emailPackages = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "com.samsung.android.email.provider", "ch.protonmail.android", "com.fsck.k9",
        "com.android.email", "me.bluemail.mail", "com.my.mail", "com.fastmail.android", "de.tutao.tutanota"
    )

    private val codeKeywords = listOf(
        "code", "verification", "verify", "otp", "one-time", "one time", "passcode", "security code",
        "2fa", "two-factor", "sign in", "sign-in", "login", "log in", "password",
        "código", "codigo", "verificación", "验证码", "校验码", "动态码", "驗證碼"
    )

    private val codeShapes = listOf(
        Regex("""(?<!\d)\d{4,8}(?!\d)"""),
        Regex("""(?<!\d)\d{3}[ -]\d{3}(?!\d)"""),
        Regex("""\b[A-Za-z]-\d{4,8}\b""")
    )

    /** Values of `ApplicationInfo.category` the app list uses; -1 means the developer did not declare one. */
    const val APP_CATEGORY_SOCIAL = 4
    const val APP_CATEGORY_MAPS = 6
    const val APP_CATEGORY_PRODUCTIVITY = 7

    private val aiWords = setOf("chatgpt", "claude", "gemini", "grok", "copilot", "perplexity", "deepseek", "cursor", "mistral")
    private val shoppingWords = setOf(
        "amazon", "walmart", "target", "ebay", "lowes", "lowe's", "swappa", "etsy", "temu", "shein",
        "costco", "aliexpress", "wayfair", "ikea", "shop", "shopping"
    )
    private val shoppingPhrases = listOf("home depot", "harbor freight", "best buy", "bed bath")
    private val healthWords = setOf(
        "health", "fitbit", "amazfit", "zepp", "dexcom", "libre", "librelink", "libreview", "garmin",
        "whoop", "oura", "strava", "fitness", "fit", "glucose", "gluclue", "medication", "pharmacy"
    )
    private val weatherWords = listOf("weather", "weawow", "wetter", "meteo", "forecast", "accuweather")
    private val utilityWords = setOf(
        "calculator", "camera", "clock", "alarm", "dolby", "files", "wallet", "maps", "notes", "drive",
        "calendar", "contacts", "dialer", "recorder", "compass", "flashlight", "translate", "gallery"
    )
    private val utilityPhrases = listOf("find hub", "find my device", "find my")

    private val knownPackages: Map<String, NotificationAppGroup> = mapOf(
        "com.openai.chatgpt" to NotificationAppGroup.AI,
        "com.anthropic.claude" to NotificationAppGroup.AI,
        "ai.x.grok" to NotificationAppGroup.AI,
        "com.google.android.apps.bard" to NotificationAppGroup.AI,
        "com.microsoft.copilot" to NotificationAppGroup.AI,
        "com.amazon.mShop.android.shopping" to NotificationAppGroup.SHOPPING,
        "com.walmart.android" to NotificationAppGroup.SHOPPING,
        "com.ebay.mobile" to NotificationAppGroup.SHOPPING,
        "com.google.android.apps.fitness" to NotificationAppGroup.HEALTH,
        "com.fitbit.FitbitMobile" to NotificationAppGroup.HEALTH,
        "com.google.android.apps.walletnfcrel" to NotificationAppGroup.UTILITIES,
        "com.google.android.apps.maps" to NotificationAppGroup.UTILITIES,
        "com.google.android.apps.docs" to NotificationAppGroup.UTILITIES,
        "com.google.android.apps.nbu.files" to NotificationAppGroup.UTILITIES
    )

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }.toSet()

    /**
     * Android's own category is only present when the developer declared it, so named apps and
     * keywords in the name come first, then the declared category, then pre-installed system apps.
     */
    fun groupFor(packageName: String, label: String, androidCategory: Int, isSystemApp: Boolean): NotificationAppGroup {
        val kind = classify(null, packageName)
        if (kind == NotificationKind.MESSAGE) return NotificationAppGroup.MESSAGING
        if (kind == NotificationKind.EMAIL) return NotificationAppGroup.EMAIL
        knownPackages[packageName]?.let { return it }

        val lowerLabel = label.lowercase()
        val lowerPackage = packageName.lowercase()
        val labelWords = words(label)
        val searchable = "$lowerLabel $lowerPackage"

        if (labelWords.any { it in aiWords }) return NotificationAppGroup.AI
        if (weatherWords.any { searchable.contains(it) }) return NotificationAppGroup.WEATHER
        if (labelWords.any { it in healthWords } || searchable.contains("health")) return NotificationAppGroup.HEALTH
        if (labelWords.any { it in shoppingWords } || shoppingPhrases.any { lowerLabel.contains(it) }) {
            return NotificationAppGroup.SHOPPING
        }
        if (labelWords.any { it in utilityWords } || utilityPhrases.any { lowerLabel.contains(it) }) {
            return NotificationAppGroup.UTILITIES
        }
        return when {
            androidCategory == APP_CATEGORY_SOCIAL -> NotificationAppGroup.MESSAGING
            androidCategory == APP_CATEGORY_MAPS || androidCategory == APP_CATEGORY_PRODUCTIVITY -> NotificationAppGroup.UTILITIES
            isSystemApp -> NotificationAppGroup.UTILITIES
            else -> NotificationAppGroup.OTHER
        }
    }

    fun classify(category: String?, packageName: String): NotificationKind = when {
        category == CATEGORY_MESSAGE -> NotificationKind.MESSAGE
        category == CATEGORY_EMAIL -> NotificationKind.EMAIL
        packageName in messagingPackages -> NotificationKind.MESSAGE
        packageName in emailPackages -> NotificationKind.EMAIL
        else -> NotificationKind.OTHER
    }

    fun containsVerificationCode(title: String, text: String): Boolean {
        val combined = "$title $text"
        val lower = combined.lowercase()
        if (codeKeywords.none { lower.contains(it) }) return false
        return codeShapes.any { it.containsMatchIn(combined) }
    }

    data class Candidate(
        val packageName: String,
        val title: String,
        val text: String,
        val ongoing: Boolean,
        val silent: Boolean,
        val groupSummary: Boolean
    )

    data class Config(
        val enabled: Boolean,
        val allowedPackages: Set<String>,
        val allowVerificationCodes: Boolean,
        val ownPackage: String
    )

    fun shouldBroadcast(candidate: Candidate, config: Config): Boolean {
        if (!config.enabled) return false
        if (candidate.packageName == config.ownPackage) return false
        if (candidate.packageName !in config.allowedPackages) return false
        if (candidate.ongoing || candidate.silent || candidate.groupSummary) return false
        if (!config.allowVerificationCodes && containsVerificationCode(candidate.title, candidate.text)) return false
        return true
    }
}
