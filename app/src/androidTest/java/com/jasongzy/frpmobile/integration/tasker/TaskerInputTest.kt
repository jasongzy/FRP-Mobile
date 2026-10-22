package com.jasongzy.frpmobile.integration.tasker

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputInfos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskerInputTest {
    @Test fun standaloneEditorUsesSelectedAppLanguage() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = AppGraph.repository(context)
        val language = repository.settings.first().languageMode
        val locales = AppCompatDelegate.getApplicationLocales()
        val monitor = instrumentation.addMonitor(TaskerActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
            repository.setLanguageMode(LanguageMode.Chinese)
            instrumentation.startActivitySync(Intent(context, TaskerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(5000) {
                while (AppCompatDelegate.getApplicationLocales()[0]?.language != "zh") delay(50)
            }
        } finally {
            repository.setLanguageMode(language)
            instrumentation.runOnMainSync {
                monitor.lastActivity?.finish()
                AppCompatDelegate.setApplicationLocales(locales)
            }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun taskerBundleRoundTripPreservesOperationAndTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commands = listOf("toggle", "start", "stop", "restart").flatMap { operation ->
            listOf(AutomationCommand(operation, "client-1"), AutomationCommand(operation, target = "autoStart"))
        } + AutomationCommand("stopAll")
        for (command in commands) {
            val saved = FrpTaskerInput(command.operation, command.profileId, command.target, "test_token".repeat(4))
            val bundle = TaskerInputInfos.fromInput(context, saved).bundle
            val restored = FrpTaskerInput()
            TaskerInputInfos.fromBundle(context, restored, bundle)
            assertEquals(command, AutomationCommand(restored.operation, restored.profileId, restored.target))
            assertEquals(saved.token, restored.token)
        }
    }
}
