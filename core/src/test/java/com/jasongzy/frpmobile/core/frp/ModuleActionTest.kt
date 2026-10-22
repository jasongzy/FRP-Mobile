package com.jasongzy.frpmobile.core.frp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ModuleActionTest {
    private val status = """{"installed":true,"enabled":true,"running":false,"version":"v1.0","versionCode":1,
        "frpVersion":"0.71.1","bundledVersion":"v1.0","bundledVersionCode":1,"bundledFrpVersion":"0.71.1","needsReinstall":false}"""

    @Test fun successfulCommandsAcceptEmptyOutputAndReadStatus() = runTest {
        for (action in listOf("status", "install", "start", "stop", "uninstall")) {
            val commands = mutableListOf<String>()
            val result = executeModuleAction(action) {
                commands += it
                if (it == "status") status else ""
            }
            assertNull(result.error)
            assertTrue(result.installed && result.enabled)
            assertFalse(result.running || result.needsReinstall)
            assertEquals("v1.0", result.version)
            assertEquals(1, result.versionCode)
            assertEquals("v1.0", result.bundledVersion)
            assertEquals(1, result.bundledVersionCode)
            assertEquals("0.71.1", result.frpVersion)
            assertEquals(if (action == "status") listOf("status") else listOf(action, "status"), commands)
        }
    }

    @Test fun commandAndStatusFailuresAreReported() = runTest {
        for (failure in listOf("install", "status")) {
            val commands = mutableListOf<String>()
            val result = executeModuleAction("install") {
                commands += it
                if (it == failure) error("ROOT_UNAVAILABLE")
                ""
            }
            assertEquals("ROOT_UNAVAILABLE", result.error)
            assertEquals(if (failure == "install") listOf("install") else listOf("install", "status"), commands)
        }
        assertEquals("EMPTY_MODULE_RESPONSE", executeModuleAction("status") { "" }.error)
        assertNotNull(executeModuleAction("status") { "{}" }.error)
        assertEquals("MAGISK_REQUIRED", executeModuleAction("status") { """{"error":"MAGISK_REQUIRED"}""" }.error)
    }

    @Test fun cancellationStopsBeforeStatusQuery() = runTest {
        val commands = mutableListOf<String>()
        try {
            executeModuleAction("install") {
                commands += it
                throw CancellationException()
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf("install"), commands)
        }
    }
}
