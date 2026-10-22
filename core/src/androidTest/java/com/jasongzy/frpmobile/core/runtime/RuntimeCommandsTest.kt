package com.jasongzy.frpmobile.core.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.FrpDatabase
import com.jasongzy.frpmobile.core.data.FrpRepository
import com.jasongzy.frpmobile.core.data.KeystoreConfigCipher
import com.jasongzy.frpmobile.core.data.SettingsStore
import com.jasongzy.frpmobile.core.frp.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RuntimeCommandsTest {
    @Test fun stopAllFollowsTheEntirePendingStartBatch() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.jasongzy.frpmobile.core.test")
        val dao = FrpDatabase.get(context).frpDao()
        val settings = SettingsStore(FrpDatabase.get(context))
        val originalSettings = settings.settings.first()
        settings.setRootMode(true)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val backend = object : FrpRuntimeGateway by FrpRuntimeManager(context) {
            override var rootMode = false
            override val bootCount = -1
            private var states = emptyList<FrpRuntimeState>()
            override suspend fun configure(profiles: List<FrpProfile>) = FrpResult(null, "")
            override suspend fun listInstances() = FrpRuntimeQueryResult.Success(states)
            override suspend fun start(profile: FrpProfile): FrpResult {
                calls += profile.id
                entered.complete(Unit)
                gate.await()
                states += FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null, true)
                return FrpResult(null, "")
            }
            override suspend fun stopAll(): FrpResult {
                calls += "stopAll"
                states = emptyList()
                return FrpResult(null, "")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = FrpRepository(dao, settings, backend, KeystoreConfigCipher(), scope)
        val field = AppGraph.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val previous = field.get(AppGraph)
        field.set(AppGraph, repository)
        val ids = listOf("retry-a", "retry-b")
        try {
            ids.forEach { id ->
                repository.createProfile(FrpProfile(id, id, FrpType.Server, "bindPort = 7000", false, 0))
                repository.requestRun(id)
            }
            repository.setPendingStart(true)
            withTimeout(10_000) {
                val retry = FrpForegroundService.retryPendingStart(context)
                entered.await()
                val stop = FrpForegroundService.stopAll(context)
                gate.complete(Unit)
                assertTrue(retry.await().isSuccess)
                assertTrue(stop.await().isSuccess)
            }
            assertEquals(ids.toSet(), calls.dropLast(1).toSet())
            assertEquals(3, calls.size)
            assertEquals("stopAll", calls.last())
            assertTrue(repository.getDesiredRunningProfiles().isEmpty())
            assertFalse(settings.settings.first().pendingStart)
        } finally {
            gate.complete(Unit)
            FrpForegroundService.stopAll(context).await()
            ids.forEach { dao.deleteConfiguration(it) }
            dao.upsertSettings(originalSettings)
            field.set(AppGraph, previous)
            scope.cancel()
        }
    }
}
