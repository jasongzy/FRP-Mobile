package com.jasongzy.frpmobile.integration.home

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.jasongzy.frpmobile.MainActivity
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.UUID

class HomeActionsTest {
    @Test fun widgetSettingsAndPendingIntentsStayIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ids = intArrayOf(900001, 900002)
        val first = AutomationCommand("start", "widget-first")
        val second = AutomationCommand("stop", "widget-second")
        try {
            HomeWidgets.save(context, ids[0], first)
            HomeWidgets.save(context, ids[1], second)
            val intents = ids.map {
                PendingIntent.getActivity(context, it, HomeWidgets.actionIntent(context, it),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            assertNotEquals(intents[0], intents[1])
            HomeWidgetProvider().onDeleted(context, intArrayOf(ids[0]))
            assertNull(HomeWidgets.read(context, ids[0]))
            assertEquals(second, HomeWidgets.read(context, ids[1]))
        } finally { HomeWidgetProvider().onDeleted(context, ids) }
    }

    @Test fun shortcutsAndToggleUseTheSavedProfile() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = AppGraph.repository(context)
        val profileId = "home-test-${UUID.randomUUID()}"
        val shortcutId = UUID.randomUUID().toString()
        val widgetId = 900003
        val port = ServerSocket(0).use { it.localPort }
        val profile = FrpProfile(profileId, "Home test", FrpType.Server,
            "bindAddr = \"127.0.0.1\"\nbindPort = $port\n", false, System.currentTimeMillis())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor = instrumentation.addMonitor(HomeActionActivity::class.java.name, null, false)
        try {
            repository.createProfile(profile)
            val action = AutomationCommand("toggle", profileId)
            HomeShortcuts.save(context, shortcutId, action, profile.name)
            val shortcut = HomeShortcuts.list(context).single { it.id == shortcutId }
            assertEquals(action, decodeHomeAction(shortcut.intent!!.getStringExtra(EXTRA_HOME_ACTION)))
            assertEquals("${profile.name} · ${context.getString(R.string.home_toggle)}", shortcut.shortLabel.toString())
            val component = action.homeIntent(context).component!!
            assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
            instrumentation.runOnMainSync { context.startActivity(action.homeIntent(context)) }
            val execution = checkNotNull(monitor.waitForActivityWithTimeout(5000))
            withTimeout(10_000) {
                repository.runtimeStates.first { states -> states.any { it.id == profileId && it.state == FrpInstanceStatus.Running } }
                while (!execution.isDestroyed) delay(25)
            }
            HomeWidgets.save(context, widgetId, action)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { context.startActivity(HomeWidgets.actionIntent(context, widgetId)) }
            withTimeout(10_000) {
                repository.runtimeStates.first { states -> states.none { it.id == profileId && it.canStop } }
            }
            repository.deleteProfile(profileId)
            HomeShortcuts.refresh(context, repository.profiles.first().associate { it.id to it.name })
            assertFalse(HomeShortcuts.list(context).any { it.id == shortcutId })
        } finally {
            HomeWidgetProvider().onDeleted(context, intArrayOf(widgetId))
            instrumentation.removeMonitor(monitor)
            HomeShortcuts.remove(context, shortcutId)
            repository.deleteProfile(profileId)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun groupToggleUsesCurrentAutoStartProfilesAndStopAllIncludesOtherProfiles() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = AppGraph.repository(context)
        val profiles = (0..2).map { index ->
            val port = ServerSocket(0).use { it.localPort }
            FrpProfile("home-group-${UUID.randomUUID()}", "Group $index", FrpType.Server,
                "bindAddr = \"127.0.0.1\"\nbindPort = $port\n", index < 2, System.currentTimeMillis())
        }
        val shortcutId = UUID.randomUUID().toString()
        val group = AutomationCommand("toggle", target = "autoStart")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertTrue(repository.profiles.first().isEmpty())
            HomeShortcuts.save(context, shortcutId, group, null)
            HomeShortcuts.refresh(context, emptyMap())
            val shortcut = HomeShortcuts.list(context).single { it.id == shortcutId }
            assertEquals(context.getString(R.string.home_action_label, context.getString(R.string.home_target_autostart),
                context.getString(R.string.home_toggle)), shortcut.shortLabel.toString())
            assertEquals(context.getString(R.string.home_action_label, context.getString(R.string.settings_target_autostart),
                context.getString(R.string.home_toggle)), shortcut.longLabel.toString())
            assertEquals("NO_TARGETS", FrpForegroundService.controlFromApp(context, group).await().code)
            profiles.forEach { repository.createProfile(it) }
            for (index in listOf(0, 2)) {
                val result = FrpForegroundService.startProfile(context, profiles[index].id).await()
                assertTrue(result.message, result.isSuccess)
            }
            val denied = FrpForegroundService.control(context, group, "invalid-token").await()
            assertEquals("ACCESS_DENIED", denied.code)
            val toggle = FrpForegroundService.controlFromApp(context, group).await()
            assertTrue(toggle.message, toggle.isSuccess)
            assertEquals(1, toggle.startedCount)
            assertEquals(1, toggle.stoppedCount)
            assertEquals(setOf(profiles[1].id, profiles[2].id),
                repository.runtimeStates.first().filter { it.canStop }.map { it.id }.toSet())
            val stop = FrpForegroundService.controlFromApp(context, AutomationCommand("stopAll")).await()
            assertTrue(stop.message, stop.isSuccess)
            assertTrue(repository.runtimeStates.first().none { it.canStop })
        } finally {
            HomeShortcuts.remove(context, shortcutId)
            profiles.forEach { repository.deleteProfile(it.id) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun actionFeedbackReportsCompletedOperationsAndLocalizedFailures() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = AppGraph.repository(context)
        val localized = context.homeContext(repository.settings.first().languageMode)
        val profiles = (0..1).map { index ->
            val port = ServerSocket(0).use { it.localPort }
            FrpProfile("home-feedback-${UUID.randomUUID()}", "Feedback $index", FrpType.Server,
                "bindAddr = \"127.0.0.1\"\nbindPort = $port\n", true, System.currentTimeMillis())
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val widgetId = 900004
        suspend fun feedback(intent: Intent): String {
            val model = HomeActionViewModel(context.applicationContext as Application)
            withContext(Dispatchers.Main) { model.run(intent) }
            return withTimeout(10_000) { model.message.first { it != null }!! }
        }
        try {
            val single = AutomationCommand("toggle", profiles[0].id)
            val group = AutomationCommand("toggle", target = "autoStart")
            for (language in listOf(LanguageMode.Chinese, LanguageMode.English)) {
                val localizedContext = context.homeContext(language)
                val empty = FrpForegroundService.controlFromApp(localizedContext, group).await()
                assertEquals("NO_TARGETS", empty.code)
                assertEquals(localizedContext.getString(com.jasongzy.frpmobile.core.R.string.automation_no_targets), empty.message)
            }
            profiles.forEach { repository.createProfile(it) }
            assertEquals(localized.getString(R.string.dashboard_profile_started, profiles[0].name),
                feedback(single.homeIntent(context)))
            assertEquals(listOf(
                localized.resources.getQuantityString(R.plurals.home_profiles_started, 1, 1),
                localized.resources.getQuantityString(R.plurals.home_profiles_stopped, 1, 1),
            ).joinToString(" · "), feedback(group.homeIntent(context)))
            HomeWidgets.save(context, widgetId, AutomationCommand("toggle", profiles[1].id))
            assertEquals(localized.getString(R.string.dashboard_profile_stopped, profiles[1].name),
                feedback(HomeWidgets.actionIntent(context, widgetId)))
            assertEquals(localized.resources.getQuantityString(R.plurals.home_profiles_started, 2, 2),
                feedback(group.homeIntent(context)))
            assertEquals(localized.resources.getQuantityString(R.plurals.home_profiles_stopped, 2, 2),
                feedback(group.homeIntent(context)))
            for ((operation, messageRes) in listOf(
                "start" to R.string.dashboard_profile_started,
                "restart" to R.string.dashboard_profile_restarted,
                "stop" to R.string.dashboard_profile_stopped,
            )) {
                assertEquals(localized.getString(messageRes, profiles[0].name),
                    feedback(AutomationCommand(operation, profiles[0].id).homeIntent(context)))
                assertEquals(localized.getString(messageRes, localized.getString(R.string.settings_target_autostart)),
                    feedback(AutomationCommand(operation, target = "autoStart").homeIntent(context)))
            }
            assertEquals(localized.getString(R.string.dashboard_all_stopped),
                feedback(AutomationCommand("stopAll").homeIntent(context)))
            repository.upsertProfile(profiles[0].copy(toml = "bindPort = 70000\n"))
            val partial = FrpForegroundService.controlFromApp(context, group).await()
            assertEquals("CONTROL_FAILED", partial.code)
            assertEquals(1, partial.startedCount)
            assertEquals(0, partial.stoppedCount)
            assertTrue(feedback(AutomationCommand("start", profiles[0].id).homeIntent(context))
                .startsWith(localized.getString(R.string.settings_operation_failed, "")))
        } finally {
            HomeWidgetProvider().onDeleted(context, intArrayOf(widgetId))
            profiles.forEach { repository.deleteProfile(it.id) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
