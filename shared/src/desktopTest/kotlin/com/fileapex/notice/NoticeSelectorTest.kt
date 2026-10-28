package com.fileapex.notice

import com.fileapex.notice.NoticeSamples.context
import com.fileapex.notice.NoticeSamples.notice
import com.fileapex.notice.NoticeSamples.parsed
import com.fileapex.notice.NoticeSamples.payload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeSelectorTest {
    private fun shown(extra: String = "", expires: String = "2027-03-01", ctx: NoticeContext = context()) =
        NoticeSelector.applicable(parsed(payload(1, NoticeSamples.ISSUED, notice(extra = extra, expires = expires))), ctx).isNotEmpty()

    @Test
    fun expiredNoticesAreIgnoredOnTheExpiryDayItself() {
        assertTrue(shown(expires = "2026-10-29"))
        assertFalse(shown(expires = "2026-10-28"))
        assertFalse(shown(expires = "2026-10-28", ctx = context(nowEpochMs = NoticeSamples.nowMs + 86_400_000L)))
    }

    @Test
    fun versionRangeAppliesToTheRunningBuild() {
        val range = ""","from":"0.19.9","below":"0.20.0""""
        assertTrue(shown(range, ctx = context("0.19.9")))
        assertTrue(shown(range, ctx = context("0.19.9a")))
        assertFalse(shown(range, ctx = context("0.19.8b")))
        assertFalse(shown(range, ctx = context("0.20.0")))
    }

    @Test
    fun platformAndChannelFilters() {
        val extra = ""","platforms":["android","linux"],"channels":["play"]"""
        assertTrue(shown(extra, ctx = context(platform = NoticePlatform.Android, channel = NoticeChannel.Play)))
        assertFalse(shown(extra, ctx = context(platform = NoticePlatform.Macos, channel = NoticeChannel.Play)))
        assertFalse(shown(extra, ctx = context(platform = NoticePlatform.Android, channel = NoticeChannel.Github)))
    }

    @Test
    fun requiresNeedsAnyOneListedFeatureAndFollowsTheSettingsLive() {
        val extra = ""","requires":["google_link","iroh"]"""
        assertFalse(shown(extra, ctx = context(features = emptySet())))
        assertFalse(shown(extra, ctx = context(features = setOf(NoticeFeature.Tailscale))))
        assertTrue(shown(extra, ctx = context(features = setOf(NoticeFeature.GoogleLink))))
        assertTrue(shown(extra, ctx = context(features = setOf(NoticeFeature.Iroh, NoticeFeature.Tailscale))))
        assertTrue(shown("", ctx = context(features = emptySet())))
    }

    @Test
    fun requiredComesFirstThenFileOrder() {
        val list = parsed(
            payload(
                1, NoticeSamples.ISSUED,
                notice(id = "i1", severity = "info"), notice(id = "r1", severity = "required"),
                notice(id = "m1", severity = "important"), notice(id = "r2", severity = "required"),
                notice(id = "i2", severity = "info")
            )
        )
        assertEquals(listOf("r1", "r2", "m1", "i1", "i2"), NoticeSelector.applicable(list, context()).map { it.id })
    }

    @Test
    fun onlyApplicableNoticesComeOut() {
        val list = parsed(
            payload(
                1, NoticeSamples.ISSUED,
                notice(id = "old", extra = ""","to":"0.18.1""""), notice(id = "new", extra = ""","from":"0.19.9""""),
                notice(id = "gone", expires = "2026-10-01")
            )
        )
        assertEquals(listOf("new"), NoticeSelector.applicable(list, context("0.19.9")).map { it.id })
    }

    @Test
    fun anUnparseableRunningVersionSeesNothing() {
        val list = parsed(payload(1, NoticeSamples.ISSUED, notice()))
        assertTrue(NoticeSelector.applicable(list, context().copy(version = null)).isEmpty())
    }
}
