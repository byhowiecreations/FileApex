package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryBulletinPolicyTest {

    @Test
    fun postsWhenAtOrBelowThresholdAndNotYetAlerted() {
        assertTrue(BatteryBulletinPolicy.shouldPostAlert(15, charging = false, alreadyAlertedThisCycle = false))
        assertTrue(BatteryBulletinPolicy.shouldPostAlert(8, charging = false, alreadyAlertedThisCycle = false))
    }

    @Test
    fun skipsWhenAboveThresholdChargingOrAlreadyAlerted() {
        assertFalse(BatteryBulletinPolicy.shouldPostAlert(16, charging = false, alreadyAlertedThisCycle = false))
        assertFalse(BatteryBulletinPolicy.shouldPostAlert(15, charging = true, alreadyAlertedThisCycle = false))
        assertFalse(BatteryBulletinPolicy.shouldPostAlert(10, charging = false, alreadyAlertedThisCycle = true))
        assertFalse(BatteryBulletinPolicy.shouldPostAlert(null, charging = false, alreadyAlertedThisCycle = false))
    }

    @Test
    fun clearsCycleOnlyAfterChargeAboveThreshold() {
        assertTrue(BatteryBulletinPolicy.shouldClearAlertedCycle(charging = true, levelPercent = 16))
        assertTrue(BatteryBulletinPolicy.shouldClearAlertedCycle(charging = true, levelPercent = null))
        assertFalse(BatteryBulletinPolicy.shouldClearAlertedCycle(charging = true, levelPercent = 15))
        assertFalse(BatteryBulletinPolicy.shouldClearAlertedCycle(charging = false, levelPercent = 40))
    }

    @Test
    fun noJobAboveStepDownOrAfterAlert() {
        assertNull(
            BatteryBulletinPolicy.jobIntervalMs(80, charging = false, alreadyAlertedThisCycle = false)
        )
        assertNull(
            BatteryBulletinPolicy.jobIntervalMs(26, charging = false, alreadyAlertedThisCycle = false)
        )
        assertNull(
            BatteryBulletinPolicy.jobIntervalMs(18, charging = true, alreadyAlertedThisCycle = false)
        )
        assertNull(
            BatteryBulletinPolicy.jobIntervalMs(12, charging = false, alreadyAlertedThisCycle = true)
        )
    }

    @Test
    fun jobStepsDownFrom25To15() {
        assertEquals(
            BatteryBulletinPolicy.JOB_INTERVAL_AT_25_MS,
            BatteryBulletinPolicy.jobIntervalMs(25, charging = false, alreadyAlertedThisCycle = false)
        )
        assertEquals(
            BatteryBulletinPolicy.JOB_INTERVAL_AT_20_MS,
            BatteryBulletinPolicy.jobIntervalMs(20, charging = false, alreadyAlertedThisCycle = false)
        )
        assertEquals(
            BatteryBulletinPolicy.JOB_INTERVAL_AT_15_MS,
            BatteryBulletinPolicy.jobIntervalMs(15, charging = false, alreadyAlertedThisCycle = false)
        )
    }
}
