package com.fileapex.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBroadcastPolicyTest {

    private val config = NotificationBroadcastPolicy.Config(
        enabled = true,
        allowedPackages = setOf("com.whatsapp", "com.example.bank"),
        allowVerificationCodes = false,
        ownPackage = "com.fileapex"
    )

    private fun candidate(
        pkg: String = "com.whatsapp",
        title: String = "Sam",
        text: String = "See you at 6",
        ongoing: Boolean = false,
        silent: Boolean = false,
        groupSummary: Boolean = false
    ) = NotificationBroadcastPolicy.Candidate(pkg, title, text, ongoing, silent, groupSummary)

    @Test
    fun plainMessageFromAllowedAppIsSent() {
        assertTrue(NotificationBroadcastPolicy.shouldBroadcast(candidate(), config))
    }

    @Test
    fun nothingIsSentWhileDisabled() {
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(candidate(), config.copy(enabled = false)))
    }

    @Test
    fun appsAreOffUnlessAllowed() {
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(candidate(pkg = "com.other"), config))
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(candidate(), config.copy(allowedPackages = emptySet())))
    }

    @Test
    fun ongoingSilentSummaryAndOwnNotificationsAreSkipped() {
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(candidate(ongoing = true), config))
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(candidate(silent = true), config))
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(candidate(groupSummary = true), config))
        assertFalse(
            NotificationBroadcastPolicy.shouldBroadcast(
                candidate(pkg = "com.fileapex"),
                config.copy(allowedPackages = config.allowedPackages + "com.fileapex")
            )
        )
    }

    @Test
    fun verificationCodesStayOnThePhoneByDefault() {
        val code = candidate(pkg = "com.example.bank", text = "Your verification code is 482913")
        assertFalse(NotificationBroadcastPolicy.shouldBroadcast(code, config))
        assertTrue(NotificationBroadcastPolicy.shouldBroadcast(code, config.copy(allowVerificationCodes = true)))
    }

    @Test
    fun codeShapesAreRecognised() {
        assertTrue(NotificationBroadcastPolicy.containsVerificationCode("Google", "G-123456 is your Google verification code"))
        assertTrue(NotificationBroadcastPolicy.containsVerificationCode("Bank", "Use 123 456 to sign in"))
        assertTrue(NotificationBroadcastPolicy.containsVerificationCode("Banco", "Tu código es 0042"))
        assertTrue(NotificationBroadcastPolicy.containsVerificationCode("银行", "您的验证码是 889102"))
    }

    @Test
    fun ordinaryNumbersAreNotMistakenForCodes() {
        assertFalse(NotificationBroadcastPolicy.containsVerificationCode("Sam", "Meet at 1234 Main Street"))
        assertFalse(NotificationBroadcastPolicy.containsVerificationCode("Mail", "You have 12 new messages"))
        assertFalse(NotificationBroadcastPolicy.containsVerificationCode("Delivery", "Your code word is banana"))
    }

    @Test
    fun categoryWinsAndKnownPackagesFillTheGap() {
        assertEquals(NotificationKind.MESSAGE, NotificationBroadcastPolicy.classify("msg", "com.unknown"))
        assertEquals(NotificationKind.EMAIL, NotificationBroadcastPolicy.classify("email", "com.unknown"))
        assertEquals(NotificationKind.MESSAGE, NotificationBroadcastPolicy.classify(null, "com.whatsapp"))
        assertEquals(NotificationKind.EMAIL, NotificationBroadcastPolicy.classify(null, "com.google.android.gm"))
        assertEquals(NotificationKind.EMAIL, NotificationBroadcastPolicy.classify(null, "ch.protonmail.android"))
        assertEquals(NotificationKind.EMAIL, NotificationBroadcastPolicy.classify(null, "de.tutao.tutanota"))
        assertEquals(NotificationKind.MESSAGE, NotificationBroadcastPolicy.classify(null, "com.discord"))
        assertEquals(NotificationKind.OTHER, NotificationBroadcastPolicy.classify(null, "com.example.game"))
        assertEquals(NotificationKind.OTHER, NotificationBroadcastPolicy.classify("promo", "com.example.game"))
    }

    private fun group(pkg: String, label: String, category: Int = -1, system: Boolean = false) =
        NotificationBroadcastPolicy.groupFor(pkg, label, category, system)

    @Test
    fun appGroupsFollowNamesKeywordsDeclaredCategoryAndSystemFlag() {
        assertEquals(NotificationAppGroup.MESSAGING, group("com.reddit.frontpage", "Reddit"))
        assertEquals(NotificationAppGroup.EMAIL, group("ch.protonmail.android", "Proton Mail"))
        assertEquals(NotificationAppGroup.AI, group("com.anthropic.claude", "Claude"))
        assertEquals(NotificationAppGroup.AI, group("com.example.x", "Grok"))
        assertEquals(NotificationAppGroup.WEATHER, group("com.example.w", "WeatherFast"))
        assertEquals(NotificationAppGroup.WEATHER, group("com.example.w2", "Weawow"))
        assertEquals(NotificationAppGroup.WEATHER, group("com.example.w3", "Pixel Weather"))
        assertEquals(NotificationAppGroup.SHOPPING, group("com.thehomedepot", "The Home Depot"))
        assertEquals(NotificationAppGroup.SHOPPING, group("com.example.s", "Harbor Freight Tools"))
        assertEquals(NotificationAppGroup.SHOPPING, group("com.target.ui", "Target"))
        assertEquals(NotificationAppGroup.HEALTH, group("com.example.h", "Dexcom G7"))
        assertEquals(NotificationAppGroup.HEALTH, group("com.example.h2", "Amazfit"))
        assertEquals(NotificationAppGroup.HEALTH, group("com.example.gc", "GluClue"))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.c", "Calculator"))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.f", "Files by Google"))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.fh", "Find Hub"))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.wl", "Samsung Wallet"))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.m", "Maps"))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.sys", "Dolby Atmos", system = true))
        assertEquals(NotificationAppGroup.UTILITIES, group("com.example.pre", "Some Vendor Tool", system = true))
        assertEquals(NotificationAppGroup.MESSAGING, group("com.example.soc", "Some Network", category = 4))
        assertEquals(NotificationAppGroup.OTHER, group("com.example.game", "Block Puzzle"))
    }

    @Test
    fun ordinaryAppsAreNotPulledIntoTheWrongGroup() {
        assertEquals(NotificationAppGroup.OTHER, group("com.example.t", "Aim Ads Blocker Pro"))
        assertEquals(NotificationAppGroup.OTHER, group("com.example.fit", "Outfit Maker"))
    }
}
