package com.jasongzy.frpmobile.core.runtime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AutomationReceiverTest {
    @Test fun slowCommandFinishesAfterBroadcastDeadline() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jasongzy.frpmobile.core.test")
        val settings = SettingsStore(FrpDatabase.get(context))
        val originalSettings = settings.settings.first()
        settings.setRootMode(true)
        val gate = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val backend = object : FrpRuntimeGateway by FrpRuntimeManager(context) {
            override var rootMode = false
            @Volatile var states = listOf(FrpRuntimeState("server", FrpType.Server, FrpInstanceStatus.Running, null, true))
            @Volatile var stopCalls = 0
            override suspend fun listInstances() = FrpRuntimeQueryResult.Success(states)
            override suspend fun stopAll(): FrpResult {
                stopCalls++
                gate.await()
                states = emptyList()
                stopped.complete(Unit)
                return FrpResult(null, "")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = FrpRepository(FrpDatabase.get(context).frpDao(), settings, backend, KeystoreConfigCipher(), scope)
        val field = AppGraph.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val previous = field.get(AppGraph)
        field.set(AppGraph, repository)
        suspend fun broadcast(token: String): Pair<Int, String?> {
            val result = CompletableDeferred<Pair<Int, String?>>()
            context.sendOrderedBroadcast(
                Intent(context, AutomationReceiver::class.java).setAction(AutomationReceiver.ACTION)
                    .putExtra("operation", "stopAll").putExtra("token", token),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        result.complete(resultCode to resultData)
                    }
                },
                null, Activity.RESULT_CANCELED, null, null,
            )
            return withTimeout(10_000) { result.await() }
        }
        try {
            repository.syncRuntimeStates()
            repository.setAutomationEnabled(true)
            assertEquals(Activity.RESULT_CANCELED to "ACCESS_DENIED", broadcast("invalid"))
            assertEquals(0, backend.stopCalls)
            val token = repository.automationToken()
            assertEquals(Activity.RESULT_OK to "ACCEPTED", broadcast(token))
            assertEquals(1, backend.stopCalls)
            assertFalse(stopped.isCompleted)
            assertEquals(Activity.RESULT_CANCELED to "ACCESS_DENIED", broadcast("invalid"))
            assertEquals(Activity.RESULT_OK to "ACCEPTED", broadcast(token))
            repository.setAutomationEnabled(false)
            gate.complete(Unit)
            withTimeout(5000) {
                stopped.await()
                assertTrue(FrpForegroundService.stopAll(context).await().isSuccess)
                while (repository.runtimeStates.first().any { it.canStop }) delay(25)
            }
            assertEquals(2, backend.stopCalls)
        } finally {
            gate.complete(Unit)
            FrpForegroundService.stopAll(context).await()
            FrpDatabase.get(context).frpDao().deleteRuntimeState("server")
            FrpDatabase.get(context).frpDao().upsertSettings(originalSettings)
            field.set(AppGraph, previous)
            scope.cancel()
        }
    }
}
