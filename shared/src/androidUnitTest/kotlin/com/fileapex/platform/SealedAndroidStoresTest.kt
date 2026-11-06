package com.fileapex.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SealedAndroidStoresTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private var sealer: FieldSealer? = null

    @Before
    fun setUp() {
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        SealedText.sealerProvider = { sealer }
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun workStateIsStoredSealedAndReadsBack() {
        AndroidWorkState.memory(context).markScheduled("backup")
        assertFalse(prefs("fileapex_work_state").getString("backup", "")!!.contains("scheduled"))
        assertTrue(AndroidWorkState.memory(context).cancelNeeded("backup"))
        AndroidWorkState.memory(context).markIdle("backup")
        assertFalse(AndroidWorkState.memory(context).cancelNeeded("backup"))
    }

    @Test
    fun aPlainWorkStateFromAnOlderVersionIsReadAndSealed() {
        prefs("fileapex_work_state").edit().putString("drive", "scheduled").commit()
        assertTrue(AndroidWorkState.memory(context).cancelNeeded("drive"))
        assertFalse(prefs("fileapex_work_state").getString("drive", "")!!.contains("scheduled"))
    }

    @Test
    fun aLockedKeyLeavesSealedWorkStateUntouchedAndAsksWorkManager() {
        AndroidWorkState.memory(context).markScheduled("drive")
        val stored = prefs("fileapex_work_state").getString("drive", "")
        sealer = null
        val memory = AndroidWorkState.memory(context)
        memory.markIdle("drive")
        assertEquals(stored, prefs("fileapex_work_state").getString("drive", ""))
        assertFalse("sealed value unreadable, and the test has no work database", memory.cancelNeeded("drive"))
    }

    @Test
    fun aPlainWorkStateStaysReadableWhileTheKeyIsLocked() {
        prefs("fileapex_work_state").edit().putString("drive", "scheduled").commit()
        sealer = null
        assertTrue(AndroidWorkState.memory(context).cancelNeeded("drive"))
        assertEquals("scheduled", prefs("fileapex_work_state").getString("drive", ""))
    }

    @Test
    fun directShareCountsAreSealedAndFoldInTheOldPlainSlots() {
        prefs("fileapex_direct_share_usage").edit().putInt("share_count_dev-old", 4).commit()
        DirectShareUsageStore.recordShare(context, "dev-old")
        assertEquals(5, DirectShareUsageStore.shareCount(context, "dev-old"))
        val all = prefs("fileapex_direct_share_usage").all
        assertTrue(all.keys.none { it.startsWith("share_count_") })
        assertTrue(all.values.none { it.toString().contains("dev-old") })
        DirectShareUsageStore.clearShareCount(context, "dev-old")
        assertEquals(0, DirectShareUsageStore.shareCount(context, "dev-old"))
    }
}
