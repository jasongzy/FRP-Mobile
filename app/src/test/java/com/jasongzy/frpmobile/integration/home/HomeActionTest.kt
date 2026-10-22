package com.jasongzy.frpmobile.integration.home

import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.integration.AUTO_START_TARGET
import com.jasongzy.frpmobile.integration.controlCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeActionTest {
    @Test fun savedActionsPreserveOperationsAndTargets() {
        for (operation in AutomationCommand.operations) {
            for (target in listOf(AUTO_START_TARGET, "frps-test_1")) {
                val command = controlCommand(operation, target)
                assertEquals(command, decodeHomeAction(command.encodeHomeAction()))
                if (operation == "stopAll") assertEquals(AutomationCommand("stopAll"), command)
            }
        }
        for (value in listOf(null, "", "toggle:profile", "Toggle:profile:", "stop:../config:",
            "toggle:a:b", "toggle::", "stopAll:a:", "unknown:profile:", "start:a::")) {
            assertNull(decodeHomeAction(value))
        }
    }
}
