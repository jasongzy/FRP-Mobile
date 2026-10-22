package com.jasongzy.frpmobile.core.frp

import org.junit.Assert.*
import org.junit.Test

class FrpResultTest {
    @Test fun protocolPreservesSuccessAndDiagnostics() {
        assertTrue(FrpResult.fromRaw("").isSuccess)
        val error = FrpResult.fromRaw("INVALID_TOML: missing serverAddr")
        assertEquals("INVALID_TOML", error.code)
        assertEquals("INVALID_TOML: missing serverAddr", error.message)
        assertEquals("ROOT_REQUIRED", FrpResult.fromRaw("ROOT_REQUIRED").code)
    }

    @Test fun recoveryExcludesSuccessAndPermanentConfigurationErrors() {
        assertFalse(FrpResult(null, "").isRetryable)
        for (code in listOf("INVALID_TOML", "TLS_FILE_MISSING", "CONFIG_DECRYPT_FAILED", "CONFIG_UNAVAILABLE", "MODULE_DISABLED")) {
            assertFalse(FrpResult(code, code).isRetryable)
        }
        assertTrue(FrpResult("DISPATCH_FAILED", "Background start restricted").isRetryable)
    }
}
