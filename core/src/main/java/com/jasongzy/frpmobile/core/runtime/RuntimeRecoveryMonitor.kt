package com.jasongzy.frpmobile.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.WorkManager
import com.jasongzy.frpmobile.core.frp.RecoveryReason
import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

class RuntimeRecoveryMonitor private constructor(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val repository = AppGraph.repository(context)
        scope.launch {
            repository.ensureRuntimeReady()
            val enabled = combine(repository.settings, repository.runtimeStates) { settings, states ->
                !settings.rootMode && settings.networkReconnectEnabled && states.any { it.desiredRunning }
            }
            networkRecoveryEvents(enabled, networkChanges(manager)).collect { reconnectRunningProfiles() }
        }

        scope.launch {
            repository.ensureRuntimeReady()
            val workManager = WorkManager.getInstance(context)
            combine(repository.settings, repository.runtimeStates, workManager.getWorkInfosByTagFlow("frp_recovery")) { settings, states, work ->
                val tokens = states.filter { it.desiredRunning }.map { "frp_recovery_token_${it.runToken}" }.toSet()
                work.filter { info ->
                    !info.state.isFinished && (settings.rootMode || info.tags.none { it in tokens } ||
                        ("frp_recovery_AutoRetry" in info.tags && !settings.autoRetryEnabled) ||
                        ("frp_recovery_Network" in info.tags && !settings.networkReconnectEnabled))
                }.map { it.id }
            }.distinctUntilChanged().collect { ids -> ids.forEach(workManager::cancelWorkById) }
        }
    }

    private suspend fun reconnectRunningProfiles() {
        val repository = AppGraph.repository(context)
        if (repository.settings.first().rootMode || !repository.shouldReconnectOnNetworkRecovery()) return
        repository.runtimeStates.first().filter {
            it.desiredRunning && it.state !in setOf(FrpInstanceStatus.Running, FrpInstanceStatus.Stopping)
        }.forEach { state ->
            val result = FrpForegroundService.recoverProfile(context, state.id, state.runToken, attempt = 0, reason = RecoveryReason.Network).await()
            if (result.isRetryable) {
                repository.setPendingStart(true)
                FrpRetryWorker.enqueue(context, state.id, state.runToken, attempt = 1, reason = RecoveryReason.Network)
            }
        }
    }

    companion object {
        @Volatile private var instance: RuntimeRecoveryMonitor? = null

        fun start(context: Context) {
            instance ?: synchronized(this) {
                instance ?: RuntimeRecoveryMonitor(context.applicationContext).also { instance = it }
            }.start()
        }
    }
}

private fun networkChanges(manager: ConnectivityManager): Flow<Unit> = callbackFlow {
    var previousNetwork = manager.activeNetwork
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == previousNetwork) return
            previousNetwork = network
            trySend(Unit)
        }
        override fun onLost(network: Network) {
            if (previousNetwork == network) previousNetwork = null
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.buffer(Channel.CONFLATED)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun networkRecoveryEvents(enabled: Flow<Boolean>, changes: Flow<Unit>): Flow<Unit> =
    enabled.distinctUntilChanged().flatMapLatest {
        if (it) changes.debounce(5_000) else emptyFlow()
    }.buffer(0)
