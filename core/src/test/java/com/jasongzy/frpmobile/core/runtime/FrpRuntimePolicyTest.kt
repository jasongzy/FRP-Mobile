package com.jasongzy.frpmobile.core.runtime

import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpRuntimeState
import com.jasongzy.frpmobile.core.frp.FrpType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FrpRuntimePolicyTest {
    @Test
    fun moduleNotificationTracksRunningStoppingAndRecoveryUntilAllStopped() {
        val running = FrpRuntimeState("server", FrpType.Server, FrpInstanceStatus.Running, null, true)
        val stopping = running.copy(id = "client", state = FrpInstanceStatus.Stopping, desiredRunning = false)
        val retrying = running.copy(state = FrpInstanceStatus.Failed)
        assertEquals(2, FrpRuntimePolicy.moduleNotificationCount(true, listOf(running, stopping)))
        assertEquals(0, FrpRuntimePolicy.moduleNotificationCount(true, listOf(retrying)))
        assertNull(FrpRuntimePolicy.moduleNotificationCount(true, listOf(retrying.copy(desiredRunning = false))))
        assertNull(FrpRuntimePolicy.moduleNotificationCount(true, emptyList()))
        assertNull(FrpRuntimePolicy.moduleNotificationCount(false, listOf(running)))
    }

    @Test
    fun retryDelayGrowsAndCapsAtTenMinutes() {
        assertEquals(10L, FrpRuntimePolicy.retryDelaySeconds(1))
        assertEquals(30L, FrpRuntimePolicy.retryDelaySeconds(2))
        assertEquals(600L, FrpRuntimePolicy.retryDelaySeconds(6))
        assertEquals(600L, FrpRuntimePolicy.retryDelaySeconds(100))
    }

    @Test
    fun screenOffKeepAliveRequiresEnabledScreenOffAndActiveInstance() {
        assertTrue(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(true, true, true))
        assertFalse(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(false, true, true))
        assertFalse(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(true, false, true))
        assertFalse(FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(true, true, false))
    }

    @Test
    fun wifiLockRequiresScreenOffKeepAliveAndWifiDefaultNetwork() {
        assertTrue(FrpRuntimePolicy.shouldHoldWifiLock(keepAlive = true, defaultNetworkIsWifi = true))
        assertFalse(FrpRuntimePolicy.shouldHoldWifiLock(keepAlive = false, defaultNetworkIsWifi = true))
        assertFalse(FrpRuntimePolicy.shouldHoldWifiLock(keepAlive = true, defaultNetworkIsWifi = false))
    }
}
