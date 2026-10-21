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

    private val strongTriggers = Regex(
        """\b(otp|2fa|pin|passcode|code|codes|one[- ]time|verification|verify|authentication|authenticate|security code)\b|""" +
            "código|codigo|verificación|验证码|校验码|动态码|驗證碼"
    )

    /** Wording that only hints at a code ("to sign in"), so it counts only for a 6 digit or 3-3 split number. */
    private val weakTriggers = Regex("""\b(sign[- ]?in|log[- ]?in|password|two[- ]factor|confirm)\b""")

    private val notACode = Regex(
        """\b(zip|postal|post|area|country|promo|promotional|discount|coupon|referral|source|dress|qr|bar|product|tracking|gift)\s+code\b""",
        RegexOption.IGNORE_CASE
    )

    /** Stripped before matching so dates, times, years, phone numbers, money and IPs are not read as codes. */
    private val notCodeNumbers = listOf(
        Regex("""\$?\s?\d[\d,]*\.\d+"""),
        Regex("""\d+(\.\d+){2,}"""),
        Regex("""[$€£¥]\s?\d[\d,]*"""),
        Regex("""\+?\d{1,3}[-. ]?\(?\d{3}\)?[-. ]\d{3}[-. ]\d{4}"""),
        Regex("""\d{1,2}[:/-]\d{1,2}([:/-]\d{1,4})?"""),
        // A year only counts as one next to a month or a word like "in" or "since"; a bare 1900-2099 can be a code.
        Regex("""(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2},?\s+)?(19|20)\d{2}(?!\d)"""),
        Regex("""(?i)\b(in|since|year|until|copyright|©)\s+(19|20)\d{2}(?!\d)""")
    )

    private val codeShapes = listOf(
        Regex("""(?<![\d.,-])\d{4,8}(?![\d.,-]?\d)"""),
        Regex("""(?<![\d.,-])\d{3}[ -]\d{3}(?![\d.,-]?\d)"""),
        Regex("""\b[A-Za-z]-\d{4,8}(?!\d)""")
    )

    private const val TRIGGER_DISTANCE = 40

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

    /** A code-shaped number must sit within [TRIGGER_DISTANCE] characters of wording that names it a code. */
    fun containsVerificationCode(title: String, text: String): Boolean {
        val cleaned = notCodeNumbers.fold(notACode.replace("$title $text", " ")) { acc, regex -> regex.replace(acc, " ") }
        val lower = cleaned.lowercase()
        for (shape in codeShapes) {
            for (match in shape.findAll(cleaned)) {
                val digits = match.value.count { it.isDigit() }
                val start = (match.range.first - TRIGGER_DISTANCE).coerceAtLeast(0)
                val end = (match.range.last + 1 + TRIGGER_DISTANCE).coerceAtMost(lower.length)
                val near = lower.substring(start, end)
                if (strongTriggers.containsMatchIn(near)) return true
                if (digits == 6 && weakTriggers.containsMatchIn(near)) return true
            }
        }
        return false
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

    fun shouldBroadcast(candidate: Candidate, config: Config): Boolean = blockedReason(candidate, config) == null

    fun blockedReason(candidate: Candidate, config: Config): String? = when {
        !config.enabled -> "disabled"
        candidate.packageName == config.ownPackage -> "own app"
        candidate.packageName !in config.allowedPackages -> "app not allowed"
        candidate.ongoing -> "ongoing"
        candidate.silent -> "silent"
        candidate.groupSummary -> "group summary"
        !config.allowVerificationCodes && containsVerificationCode(candidate.title, candidate.text) -> "verification code"
        else -> null
    }
}
