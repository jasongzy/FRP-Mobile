package com.jasongzy.frpmobile.core.frp

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationVerificationTest {
    @Test fun verifiesDraftsWithoutStartingTheRuntime() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jasongzy.frpmobile.core.test")
        val root = File(context.noBackupFilesDir, "runtime")
        check(!root.exists())
        val runtime = FrpRuntimeManager(context)
        try {
            for (type in FrpType.entries) {
                val draft = newProfileDraft(type)
                assertTrue(runtime.verify(draft, useRoot = false).isSuccess)
                val invalid = draft.copy(toml = "unknownField = 1")
                val error = runtime.verify(invalid, useRoot = false)
                assertEquals("INVALID_TOML", error.code)
                assertTrue(error.message.contains("unknownField"))
                assertFalse(runtime.localRuntimeRunning)
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally {
            assertTrue(root.delete())
        }
    }
}
