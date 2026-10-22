package com.jasongzy.frpmobile.core.runtime

import com.jasongzy.frpmobile.core.R
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.FrpDatabase
import com.jasongzy.frpmobile.core.data.FrpRepository
import com.jasongzy.frpmobile.core.data.KeystoreConfigCipher
import com.jasongzy.frpmobile.core.data.SettingsStore
import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpResult
import com.jasongzy.frpmobile.core.frp.FrpRuntimeGateway
import com.jasongzy.frpmobile.core.frp.FrpRuntimeManager
import com.jasongzy.frpmobile.core.frp.FrpRuntimeQueryResult
import com.jasongzy.frpmobile.core.frp.FrpRuntimeState
import com.jasongzy.frpmobile.core.frp.FrpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RuntimeNotificationsTest {
    @Test fun moduleNotificationTracksBackendWithoutActivityAndStopsThroughItsPendingIntent() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jasongzy.frpmobile.core.test")
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = SettingsStore(FrpDatabase.get(context))
        settings.setRootMode(true)
        val backend = object : FrpRuntimeGateway by FrpRuntimeManager(context) {
            @Volatile override var rootMode = false
            @Volatile var states = listOf(FrpRuntimeState("server", FrpType.Server, FrpInstanceStatus.Running, null, true))
            @Volatile var stopCalls = 0
            @Volatile var queryCalls = 0
            @Volatile var stopGate: CompletableDeferred<Unit>? = null
            override suspend fun listInstances(): FrpRuntimeQueryResult {
                queryCalls++
                return FrpRuntimeQueryResult.Success(states)
            }
            override suspend fun stopAll(): FrpResult {
                stopCalls++
                stopGate?.await()
                states = emptyList()
                return FrpResult(null, "")
            }
        }
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = FrpRepository(FrpDatabase.get(context).frpDao(), settings, backend, KeystoreConfigCipher(), repositoryScope)
        val field = AppGraph.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val previous = field.get(AppGraph)
        field.set(AppGraph, repository)
        var observer = launch { RuntimeNotifications.observeModule(context) }
        try {
            repository.syncRuntimeStates()
            suspend fun moduleNotification(): StatusBarNotification = withTimeout(5000) {
                while (manager.activeNotifications.none { it.id != RuntimeNotifications.SERVICE_ID }) delay(25)
                manager.activeNotifications.single { it.id != RuntimeNotifications.SERVICE_ID }
            }
            val initial = moduleNotification()
            val first = initial.notification
            assertEquals(context.getString(R.string.notification_running_content, 1), first.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT != 0)
            val firstQueries = backend.states
            observer.cancelAndJoin()
            withTimeout(5000) { while (manager.activeNotifications.isNotEmpty()) delay(25) }
            assertEquals(firstQueries, backend.states)
            assertEquals(0, backend.stopCalls)

            observer = launch { RuntimeNotifications.observeModule(context) }
            val restored = moduleNotification().notification
            assertEquals(first.extras.getCharSequence(Notification.EXTRA_TEXT), restored.extras.getCharSequence(Notification.EXTRA_TEXT))

            backend.states = firstQueries.map { it.copy(state = FrpInstanceStatus.Stopped, desiredRunning = false) }
            withTimeout(5000) { while (manager.activeNotifications.isNotEmpty()) delay(25) }
            assertEquals(0, backend.stopCalls)
            val queriesAfterStop = backend.queryCalls
            delay(2200)
            assertEquals(queriesAfterStop, backend.queryCalls)
            observer.cancelAndJoin()
            manager.notify(initial.id, first)
            moduleNotification()
            observer = launch { RuntimeNotifications.observeModule(context) }
            withTimeout(5000) { while (manager.activeNotifications.isNotEmpty()) delay(25) }

            backend.states = firstQueries
            repository.syncRuntimeStates()
            moduleNotification()
            restored.actions.first().actionIntent.send()
            withTimeout(5000) {
                while (backend.stopCalls != 1 || manager.activeNotifications.isNotEmpty()) delay(25)
            }
            assertTrue(repository.runtimeStates.first().none { it.canStop })

            backend.states = firstQueries
            repository.syncRuntimeStates()
            moduleNotification()
            manager.notify(RuntimeNotifications.SERVICE_ID, RuntimeNotifications.build(context, 1))
            settings.setRootMode(false)
            withTimeout(5000) {
                while (manager.activeNotifications.any { it.id != RuntimeNotifications.SERVICE_ID }) delay(25)
            }
            observer.cancelAndJoin()
            assertEquals(RuntimeNotifications.SERVICE_ID, manager.activeNotifications.single().id)
            val stopGate = CompletableDeferred<Unit>()
            backend.stopGate = stopGate
            val broadcastFinished = CompletableDeferred<Unit>()
            manager.activeNotifications.single().notification.actions.first().actionIntent.send(
                context, 0, null,
                PendingIntent.OnFinished { _, _, _, _, _ -> broadcastFinished.complete(Unit) },
                Handler(Looper.getMainLooper()),
            )
            withTimeout(10_000) { broadcastFinished.await() }
            assertEquals(2, backend.stopCalls)
            assertEquals(firstQueries, backend.states)
            stopGate.complete(Unit)
            withTimeout(5000) {
                while (backend.stopCalls != 2 || repository.runtimeStates.first().any { it.canStop }) delay(25)
            }
        } finally {
            backend.stopGate?.complete(Unit)
            observer.cancelAndJoin()
            manager.cancelAll()
            settings.setRootMode(false)
            field.set(AppGraph, previous)
            repositoryScope.cancel()
        }
    }
}
