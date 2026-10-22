package com.jasongzy.frpmobile.core.frp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpRuntimeStateTest {
    @Test fun stopRemainsAvailableWhileRecoveryIsPending() {
        val failed = FrpRuntimeState("profile", FrpType.Client, FrpInstanceStatus.Failed, "offline", true)
        assertTrue(failed.canStop)
        assertFalse(failed.copy(desiredRunning = false).canStop)
        assertTrue(failed.copy(state = FrpInstanceStatus.Stopped).canStop)
        assertTrue(failed.copy(state = FrpInstanceStatus.Running, desiredRunning = false).canStop)
        assertTrue(failed.copy(state = FrpInstanceStatus.Stopping, desiredRunning = false).canStop)
    }
}
