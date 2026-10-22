package com.jasongzy.frpmobile.core.runtime

import com.jasongzy.frpmobile.core.R
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Bundle
import android.os.PowerManager
import android.os.ResultReceiver
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import com.jasongzy.frpmobile.core.frp.FrpResult
import com.jasongzy.frpmobile.core.frp.RecoveryReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class FrpForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { AppGraph.repository(this) }
    private val commandsInFlight = AtomicInteger()
    private var runtimeWatch: Job? = null
    private var notifiedCount: Int? = null
    @Volatile private var destroyed = false
    private val recoveryAttempts = ConcurrentHashMap<String, Int>()
    @Volatile private var latestStartId = 0
    private val keepAliveLock = Any()
    @Volatile
    private var keepAliveMonitoring = false
    @Volatile
    private var screenOffKeepAliveEnabled = false
    @Volatile
    private var hasActiveInstances = false
    @Volatile
    private var screenOff = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        RuntimeNotifications.createChannel(this)
        startScreenOffKeepAliveMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START_PROFILE, ACTION_RESTART_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
                promoteToForeground()
                runProfile(
                    id, intent.getStringExtra(EXTRA_RUN_TOKEN) ?: return START_NOT_STICKY,
                    intent.action == ACTION_RESTART_PROFILE,
                    IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_RECEIVER, ResultReceiver::class.java),
                )
            }
            ACTION_RECOVER_PROFILE -> {
                val id = intent.getStringExtra(EXTRA_PROFILE_ID) ?: return START_NOT_STICKY
                val attempt = intent.getIntExtra(EXTRA_RECOVERY_ATTEMPT, 0)
                val reason = intent.getStringExtra(EXTRA_RECOVERY_REASON)?.let { value ->
                    runCatching { RecoveryReason.valueOf(value) }.getOrNull()
                } ?: return START_NOT_STICKY
                promoteToForeground()
                recoverProfile(id, intent.getStringExtra(EXTRA_RUN_TOKEN) ?: return START_NOT_STICKY, attempt, reason)
            }
            ACTION_RESTORE_DESIRED -> restoreDesired()
            else -> restoreDesired()
        }
        if (runtimeWatch?.isActive != true) runtimeWatch = scope.launch {
            delay(250)
            while (isActive) {
                repository.syncRuntimeStates()
                refreshNotificationOrStop()
                if (repository.localRuntimeRunning) {
                    repository.awaitRuntimeChange()
                    delay(250)
                } else if (commandsInFlight.get() > 0) delay(250)
                else break
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        stopScreenOffKeepAliveMonitoring()
        super.onDestroy()
    }

    private fun launchCommand(block: suspend () -> Unit): Job {
        commandsInFlight.incrementAndGet()
        return scope.launch {
            try {
                block()
            } finally {
                if (commandsInFlight.decrementAndGet() == 0 && isActive) refreshNotificationOrStop()
            }
        }
    }

    private fun runProfile(id: String, token: String, restart: Boolean, receiver: ResultReceiver?) {
        recoveryAttempts[token] = 1
        var result = FrpResult("START_INTERRUPTED", "START_INTERRUPTED")
        launchCommand {
            try {
                result = repository.runProfile(id, token, restart)
                    ?: FrpResult("RUN_SUPERSEDED", "RUN_SUPERSEDED")
                if (result.isSuccess) {
                    FrpRetryWorker.cancel(this@FrpForegroundService, id, token)
                    repository.setPendingStart(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = FrpResult("START_FAILED", "START_FAILED: ${e.message}")
            }
        }.invokeOnCompletion {
            receiver?.send(0, Bundle().apply {
                putString("code", result.code)
                putString("message", result.message)
            })
        }
    }

    private fun recoverProfile(id: String, token: String, attempt: Int, reason: RecoveryReason) {
        recoveryAttempts[token] = attempt + 1
        launchCommand {
            val result = repository.runProfile(id, token, reason = reason)
            if (result?.isRetryable == true && !repository.shouldAutoRetryFailures()) {
                FrpRetryWorker.enqueue(this@FrpForegroundService, id, token, attempt + 1, reason)
            } else {
                FrpRetryWorker.cancel(this@FrpForegroundService, id, token)
            }
        }
    }

    private fun restoreDesired() {
        launchCommand {
            val desired = repository.getDesiredRunningProfiles()
            if (desired.isEmpty()) {
                repository.setPendingStart(false)
            } else {
                promoteToForeground()
            }
            repository.restoreDesiredProfiles()
        }
    }

    private suspend fun refreshNotificationOrStop() {
        if (commandsInFlight.get() > 0) return
        val startId = latestStartId
        val states = repository.runtimeStates.first()
        recoveryAttempts.keys.retainAll(states.map { it.runToken }.toSet())
        val count = states.count {
            it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping
        }
        val retrying = repository.localRuntimeRunning && repository.shouldAutoRetryFailures() &&
            states.any { it.desiredRunning }
        if (count <= 0 && !retrying) {
            if (!repository.localRuntimeRunning && repository.shouldAutoRetryFailures()) {
                states.filter { it.desiredRunning }.forEach { state ->
                    FrpRetryWorker.enqueue(this, state.id, state.runToken,
                        recoveryAttempts[state.runToken] ?: 1, RecoveryReason.AutoRetry)
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (commandsInFlight.get() == 0 && latestStartId == startId) {
                    ServiceCompat.stopForeground(this@FrpForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            return
        }
        synchronized(this) {
            if (notifiedCount != count) {
                getSystemService(NotificationManager::class.java).notify(RuntimeNotifications.SERVICE_ID, RuntimeNotifications.build(this, count))
                notifiedCount = count
            }
        }
    }

    private fun registerKeepAliveCallbacks() {
        keepAliveMonitoring = true

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff = true
                    Intent.ACTION_SCREEN_ON -> screenOff = false
                    else -> return
                }
                updateScreenOffKeepAlive()
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        screenOff = !getSystemService(PowerManager::class.java).isInteractive

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateScreenOffKeepAlive()
            override fun onLost(network: Network) = updateScreenOffKeepAlive()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                updateScreenOffKeepAlive()
        }.also { callback ->
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    private fun startScreenOffKeepAliveMonitoring() {
        scope.launch {
            repository.settings.collect { settings ->
                if (settings.rootMode) {
                    screenOffKeepAliveEnabled = false
                    stopScreenOffKeepAliveMonitoring()
                    ServiceCompat.stopForeground(this@FrpForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                screenOffKeepAliveEnabled = settings.screenOffKeepAliveEnabled
                updateScreenOffKeepAlive()
            }
        }
        scope.launch {
            repository.runtimeStates.collect { states ->
                hasActiveInstances = states.any {
                    it.state == FrpInstanceStatus.Running || it.state == FrpInstanceStatus.Stopping
                }
                updateScreenOffKeepAlive()
            }
        }
    }

    private fun stopScreenOffKeepAliveMonitoring() {
        synchronized(keepAliveLock) {
            keepAliveMonitoring = false
            screenReceiver?.let { receiver -> runCatching { unregisterReceiver(receiver) } }
            screenReceiver = null
            networkCallback?.let { callback ->
                runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
            }
            networkCallback = null
            releaseScreenOffKeepAliveLocked()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun updateScreenOffKeepAlive() {
        synchronized(keepAliveLock) {
            val needed = !destroyed && screenOffKeepAliveEnabled && hasActiveInstances
            if (needed && !keepAliveMonitoring) registerKeepAliveCallbacks()
            if (!needed && keepAliveMonitoring) stopScreenOffKeepAliveMonitoring()
            if (!keepAliveMonitoring) {
                releaseScreenOffKeepAliveLocked()
                return
            }
            val shouldKeepAlive = FrpRuntimePolicy.shouldHoldScreenOffKeepAlive(
                enabled = screenOffKeepAliveEnabled,
                screenOff = screenOff,
                hasActiveInstances = hasActiveInstances,
            )
            if (shouldKeepAlive) {
                acquireWakeLockLocked()
            } else {
                releaseWakeLockLocked()
            }

            if (FrpRuntimePolicy.shouldHoldWifiLock(shouldKeepAlive, isDefaultNetworkWifi())) {
                acquireWifiLockLocked()
            } else {
                releaseWifiLockLocked()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLockLocked() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:screen-off-keep-alive")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireWifiLockLocked() {
        if (wifiLock?.isHeld == true) return
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:screen-off-keep-alive")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseScreenOffKeepAliveLocked() {
        releaseWifiLockLocked()
        releaseWakeLockLocked()
    }

    private fun releaseWakeLockLocked() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLockLocked() {
        wifiLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wifiLock = null
    }

    private fun isDefaultNetworkWifi(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    @Synchronized
    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            RuntimeNotifications.SERVICE_ID,
            RuntimeNotifications.build(this, runningCount = notifiedCount ?: 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        const val ACTION_START_PROFILE = "com.jasongzy.frpmobile.START_PROFILE"
        const val ACTION_RESTART_PROFILE = "com.jasongzy.frpmobile.RESTART_PROFILE"
        const val ACTION_RECOVER_PROFILE = "com.jasongzy.frpmobile.RECOVER_PROFILE"
        const val ACTION_STOP_PROFILE = "com.jasongzy.frpmobile.STOP_PROFILE"
        const val ACTION_STOP_ALL = "com.jasongzy.frpmobile.STOP_ALL"
        const val ACTION_RESTORE_DESIRED = "com.jasongzy.frpmobile.RESTORE_DESIRED"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_RUN_TOKEN = "run_token"
        private const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val EXTRA_RECOVERY_ATTEMPT = "recovery_attempt"
        const val EXTRA_RECOVERY_REASON = "recovery_reason"
        const val EXTRA_START_DESTINATION = "start_destination"
        const val DESTINATION_LOGS = "logs"

        fun startProfile(context: Context, profileId: String): Deferred<FrpResult> {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_START_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            return startRuntimeService(context, intent)
        }

        fun restartProfile(context: Context, profileId: String): Deferred<FrpResult> {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_RESTART_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            return startRuntimeService(context, intent)
        }

        fun recoverProfile(context: Context, profileId: String, token: String, attempt: Int, reason: RecoveryReason): Deferred<FrpResult> {
            val intent = Intent(context, FrpForegroundService::class.java)
                .setAction(ACTION_RECOVER_PROFILE)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_RUN_TOKEN, token)
                .putExtra(EXTRA_RECOVERY_ATTEMPT, attempt)
                .putExtra(EXTRA_RECOVERY_REASON, reason.name)
            return startRuntimeService(context, intent)
        }

        fun stopProfile(context: Context, profileId: String): Deferred<FrpResult> {
            return startRuntimeService(context,
                Intent(context, FrpForegroundService::class.java)
                    .setAction(ACTION_STOP_PROFILE)
                    .putExtra(EXTRA_PROFILE_ID, profileId),
            )
        }

        fun stopAll(context: Context): Deferred<FrpResult> {
            return startRuntimeService(context, Intent(context, FrpForegroundService::class.java).setAction(ACTION_STOP_ALL))
        }

        fun restoreDesired(context: Context): Deferred<FrpResult> {
            return startRuntimeService(context,
                Intent(context, FrpForegroundService::class.java).setAction(ACTION_RESTORE_DESIRED),
            )
        }

        private val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val dispatchMutex = Mutex()

        private fun startRuntimeService(context: Context, intent: Intent): Deferred<FrpResult> = commands.async {
            dispatchMutex.withLock { dispatchLocked(context, intent) }
        }

        fun retryPendingStart(context: Context): Deferred<FrpResult> {
            val app = context.applicationContext
            return commands.async {
                dispatchMutex.withLock {
                    val repository = AppGraph.repository(app)
                    val failures = repository.getDesiredRunningProfiles().mapNotNull { profile ->
                        val result = dispatchLocked(app, Intent(app, FrpForegroundService::class.java)
                            .setAction(ACTION_START_PROFILE).putExtra(EXTRA_PROFILE_ID, profile.id))
                        if (result.isSuccess) null else "${profile.name}: ${result.message}"
                    }
                    repository.setPendingStart(failures.isNotEmpty())
                    if (failures.isEmpty()) FrpResult(null, "") else FrpResult("CONTROL_FAILED", failures.joinToString("\n"))
                }
            }
        }

        fun control(context: Context, command: AutomationCommand, token: String): Deferred<FrpResult> {
            val app = context.applicationContext
            return commands.async {
                val repository = AppGraph.repository(app)
                if (!repository.authorizeAutomation(token)) return@async FrpResult("ACCESS_DENIED", "ACCESS_DENIED")
                dispatchMutex.withLock {
                    if (!repository.authorizeAutomation(token)) return@withLock FrpResult("ACCESS_DENIED", "ACCESS_DENIED")
                    controlLocked(app, command, token = token)
                }
            }
        }

        fun startOnAppLaunch(context: Context): Deferred<FrpResult> = commands.async {
            dispatchMutex.withLock {
                if (!AppGraph.repository(context).settings.first().launchStartEnabled) return@withLock FrpResult(null, "")
                controlLocked(context, AutomationCommand("start", target = "autoStart"), skipDesired = true)
            }
        }

        private suspend fun controlLocked(
            context: Context, command: AutomationCommand, skipDesired: Boolean = false, token: String? = null,
        ): FrpResult {
            val repository = AppGraph.repository(context)
            val action = when (command.operation) {
                "start" -> ACTION_START_PROFILE
                "stop" -> ACTION_STOP_PROFILE
                "restart" -> ACTION_RESTART_PROFILE
                "stopAll" -> return dispatchLocked(context, Intent(context, FrpForegroundService::class.java).setAction(ACTION_STOP_ALL))
                else -> error("INVALID_OPERATION")
            }
            val ids = if (command.target == "autoStart") repository.getAutoStartProfiles().map { it.id }
                else listOf(command.profileId)
            if (ids.isEmpty()) return FrpResult("NO_TARGETS", context.getString(R.string.automation_no_targets))
            val desired = if (skipDesired) repository.getDesiredRunningProfiles().map { it.id }.toSet() else emptySet()
            val failures = mutableListOf<String>()
            for (id in ids) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (token != null && !repository.authorizeAutomation(token)) {
                    return FrpResult("ACCESS_DENIED", "ACCESS_DENIED")
                }
                if (id in desired) continue
                if (repository.getProfile(id) == null) {
                    failures += "$id: PROFILE_MISSING"
                    continue
                }
                val result = dispatchLocked(context, Intent(context, FrpForegroundService::class.java)
                    .setAction(action).putExtra(EXTRA_PROFILE_ID, id))
                if (!result.isSuccess) failures += "$id: ${result.message}"
            }
            return if (failures.isEmpty()) FrpResult(null, "") else FrpResult("CONTROL_FAILED", failures.joinToString("; "))
        }

        private suspend fun dispatchLocked(context: Context, intent: Intent): FrpResult {
            val app = context.applicationContext
            val repository = AppGraph.repository(app)
            val id = intent.getStringExtra(EXTRA_PROFILE_ID)
            return try {
                when (intent.action) {
                    ACTION_STOP_PROFILE -> {
                        val result = repository.getProfile(id.orEmpty())?.let { repository.stop(it) } ?: FrpResult(null, "")
                        id?.let { FrpRetryWorker.cancel(app, it) }
                        return result
                    }
                    ACTION_STOP_ALL -> {
                        val result = repository.stopAll()
                        FrpRetryWorker.cancelAll(app)
                        return result
                    }
                    ACTION_START_PROFILE, ACTION_RESTART_PROFILE -> {
                        if (repository.settings.first().rootMode) {
                            if (id == null) return FrpResult("PROFILE_MISSING", "PROFILE_MISSING")
                            FrpRetryWorker.cancel(app, id)
                            return repository.runProfile(id, restart = intent.action == ACTION_RESTART_PROFILE)
                                ?: FrpResult("PROFILE_MISSING", "PROFILE_MISSING")
                        }
                        val token = id?.let { repository.requestRun(it) }
                            ?: return FrpResult("PROFILE_MISSING", "PROFILE_MISSING")
                        FrpRetryWorker.cancel(app, id)
                        intent.putExtra(EXTRA_RUN_TOKEN, token)
                    }
                    ACTION_RECOVER_PROFILE -> {
                        val token = intent.getStringExtra(EXTRA_RUN_TOKEN) ?: return FrpResult(null, "")
                        val reason = RecoveryReason.valueOf(intent.getStringExtra(EXTRA_RECOVERY_REASON)!!)
                        if (!repository.canRecover(id.orEmpty(), token, reason)) return FrpResult(null, "")
                    }
                }
                if (repository.settings.first().rootMode) {
                    repository.syncRuntimeStates()
                    return FrpResult(null, "")
                }
                if (intent.action == ACTION_RESTORE_DESIRED && repository.getDesiredRunningProfiles().isEmpty()) {
                    repository.syncRuntimeStates()
                    repository.setPendingStart(false)
                    return FrpResult(null, "")
                }
                if (intent.action == ACTION_START_PROFILE || intent.action == ACTION_RESTART_PROFILE) {
                    val completion = CompletableDeferred<FrpResult>()
                    intent.putExtra(EXTRA_RESULT_RECEIVER, object : ResultReceiver(null) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            completion.complete(FrpResult(resultData?.getString("code"), resultData?.getString("message").orEmpty()))
                        }
                    })
                    ContextCompat.startForegroundService(app, intent)
                    withTimeoutOrNull(60_000) { completion.await() }
                        ?: FrpResult("START_TIMEOUT", "START_TIMEOUT")
                } else {
                    ContextCompat.startForegroundService(app, intent)
                    FrpResult(null, "")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.setPendingStart(true)
                val token = intent.getStringExtra(EXTRA_RUN_TOKEN)
                if (id != null && token != null && intent.action != ACTION_RECOVER_PROFILE) {
                    FrpRetryWorker.enqueue(app, id, token, 1, RecoveryReason.AutoRetry)
                }
                FrpResult("DISPATCH_FAILED", "DISPATCH_FAILED: ${e.message}")
            }
        }
    }
}
