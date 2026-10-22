package com.jasongzy.frpmobile.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutomationCommandTest {
    @Test fun generatedCommandsPreserveOperationTargetAndAuthorization() {
        val token = "test_token_".repeat(4)
        for (operation in listOf("start", "stop", "restart")) {
            for (command in listOf(AutomationCommand(operation, "client-1"), AutomationCommand(operation, target = "autoStart"))) {
                val text = command.toAdbCommand("com.jasongzy.frpmobile", token)
                assertTrue(text.startsWith("adb shell am broadcast -n com.jasongzy.frpmobile/com.jasongzy.frpmobile.core.runtime.AutomationReceiver"))
                assertTrue(text.contains(" -a com.jasongzy.frpmobile.CONTROL "))
                val extras = Regex("--es (\\w+) (?:'([^']*)'|(\\S+))").findAll(text).associate {
                    it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }
                }
                assertEquals(command, AutomationCommand(extras.getValue("operation"), extras["profile_id"].orEmpty(), extras["target"].orEmpty()))
                assertEquals(token, extras["token"])
            }
        }
        val stopAll = AutomationCommand("stopAll").toAdbCommand("com.jasongzy.frpmobile", token)
        assertTrue(stopAll.contains(" --es operation stopAll "))
        assertFalse(stopAll.contains("--es profile_id"))
        assertFalse(stopAll.contains("--es target"))
    }

    @Test fun generatedCommandsRejectUnsafeShellArguments() {
        for ((packageName, token) in listOf(
            "com.example;echo" to "a".repeat(32),
            "com.example" to "",
            "com.example" to ("a".repeat(32) + "';echo injected"),
        )) {
            try {
                AutomationCommand("stopAll").toAdbCommand(packageName, token)
                fail("Accepted unsafe shell arguments")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun malformedTargetsNeverExpandToAllProfiles() {
        for ((operation, id, target) in listOf(
            Triple("start", "", ""), Triple("stop", "", "all"), Triple("restart", "one", "autoStart"),
            Triple("start", "../one", ""), Triple("start", " ", ""), Triple("START", "one", ""),
            Triple("stopAll", "", "autoStart"), Triple("stopAll", "one", ""),
        )) {
            try {
                AutomationCommand(operation, id, target)
                fail("Accepted invalid command: $operation / $id / $target")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
