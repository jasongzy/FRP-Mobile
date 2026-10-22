package com.jasongzy.frpmobile.core.data

import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpLog
import com.jasongzy.frpmobile.core.frp.FrpLogSink
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpResult
import com.jasongzy.frpmobile.core.frp.FrpRuntimeGateway
import com.jasongzy.frpmobile.core.frp.ModuleStatus
import com.jasongzy.frpmobile.core.frp.ThemeMode
import com.jasongzy.frpmobile.core.frp.FrpRuntimeQueryResult
import com.jasongzy.frpmobile.core.frp.FrpRuntimeState
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.frp.RecoveryReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.GeneralSecurityException
import java.io.InputStream
import java.util.UUID

class FrpRepository(
    private val dao: FrpDao,
    private val settingsStore: SettingsGateway,
    private val runtimeManager: FrpRuntimeGateway,
    private val cipher: ConfigCipher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val logFlushDelayMs: Long = 750,
    private val logBatchSize: Int = 100,
    private val logBufferLimit: Int = 1_000,
    private val logTrimInterval: Int = 1_000,
    private val logMaxCount: Int = 100_000,
) {
    private val logChannel = Channel<FrpLog>(
        capacity = logBufferLimit,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val logWriteMutex = Mutex()
    private val initMutex = Mutex()
    private val runtimeOperationMutex = Mutex()
    private val startedRunTokens = mutableMapOf<String, String>()
    private var logConsumerJob: Job? = null
    private var logsSinceTrim = 0
    private var initialized = false
    private var lastQueryError: String? = null

    init {
        startLogConsumer()
    }

    val profiles: Flow<List<FrpProfile>> = dao.observeProfiles().map { entities ->
        entities.map { decodeProfile(it) }
    }.flowOn(Dispatchers.IO)

    val runtimeStates: Flow<List<FrpRuntimeState>> = dao.observeRuntimeStates().map { entities ->
        entities.map { it.toModel() }
    }

    val settings: Flow<FrpSettings> = settingsStore.settings
    val oldestLogTime: Flow<Long?> = dao.observeOldestLogTime()
    suspend fun awaitRuntimeChange() = runtimeManager.awaitRuntimeChange()
    val localRuntimeRunning: Boolean get() = runtimeManager.localRuntimeRunning

    fun observeLogs(
        instanceId: String? = null,
        type: String? = null,
        level: String? = null,
        keyword: String? = null,
        limit: Int = 200,
        maxUid: Long? = null,
    ): Flow<List<FrpLog>> = dao.observeLogs(
        instanceId = instanceId?.ifBlank { null },
        type = type?.ifBlank { null },
        level = level?.ifBlank { null },
        keyword = keyword?.ifBlank { null },
        limit = limit,
        maxUid = maxUid,
    ).map { entities -> entities.map { it.toModel() } }

    suspend fun latestLogId(): Long = withContext(Dispatchers.IO) { dao.latestLogId() }

    suspend fun createProfile(profile: FrpProfile) = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        require(dao.getProfile(profile.id) == null) { "Profile already exists" }
        validateProfile(profile)
        val certificates = encodeCertificates(profile.id, profile.files)
        val stored = profile.copy(updatedAt = System.currentTimeMillis(), files = emptyMap())
        markRuntimeDirty()
        dao.createConfiguration(encodeProfile(stored), certificates)
        stored
    } }

    suspend fun upsertProfile(profile: FrpProfile, expectedRevision: Long? = null, files: Map<String, ByteArray>? = null) = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        checkRevision(profile.id, expectedRevision)
        val stored = writeProfile(profile, files?.let { encodeCertificates(profile.id, it) })
        syncRuntimeProfiles()
        stored
    } }

    private suspend fun checkRevision(id: String, expectedRevision: Long?) {
        if (expectedRevision != null) require(dao.getProfile(id)?.updatedAt == expectedRevision) { "CONFIG_CHANGED" }
    }

    private suspend fun writeProfile(profile: FrpProfile, certificates: List<CertificateEntity>? = null): FrpProfile {
        validateProfile(profile)
        markRuntimeDirty()
        val stored = profile.copy(updatedAt = nextRevision(profile.id), files = emptyMap())
        dao.saveConfiguration(encodeProfile(stored), certificates)
        return stored
    }

    private fun encodeCertificates(id: String, files: Map<String, ByteArray>): List<CertificateEntity> =
        files.map { (name, content) ->
            require(TlsFileRole.entries.any { it.fileName == name }) { "Invalid certificate name" }
            require(content.isNotEmpty() && content.size <= 1024 * 1024) { "Invalid certificate size" }
            CertificateEntity(id, name, cipher.encrypt(content, "$id/$name"))
        }

    private suspend fun readCertificates(id: String): Map<String, ByteArray> =
        dao.getCertificates(id).associate { it.name to cipher.decrypt(it.content, "$id/${it.name}") }

    private suspend fun nextRevision(id: String): Long =
        maxOf(System.currentTimeMillis(), (dao.getProfile(id)?.updatedAt ?: 0) + 1)

    suspend fun readExternalProfiles(id: String?): List<FrpProfile> = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            if (!settings.first().externalReadEnabled) throw SecurityException("CONFIG_READ_DISABLED")
            val entities = if (id == null) dao.getProfiles() else listOfNotNull(dao.getProfile(id))
            entities.map(::decodeProfile)
        }
    }

    suspend fun writeExternalProfile(
        id: String?, name: String?, type: FrpType?, toml: String, expectedRevision: Long?,
    ): String = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            if (!settings.first().externalWriteEnabled) throw SecurityException("CONFIG_WRITE_DISABLED")
            val existing = id?.let { requireNotNull(dao.getProfile(it)) { "PROFILE_MISSING" }.let(::decodeProfile) }
            if (existing != null) {
                check(!existing.storageError) { "CONFIG_DECRYPT_FAILED" }
                require(expectedRevision == existing.updatedAt) { "CONFIG_CHANGED" }
            }
            val profile = existing?.copy(name = name ?: existing.name, toml = toml)
                ?: FrpProfile(UUID.randomUUID().toString(), requireNotNull(name), requireNotNull(type), toml, false, 0)
            validateProfile(profile)
            val validation = validateToml(toml)
            require(validation.isSuccess) { validation.message }
            writeProfile(profile)
            if (existing != null) syncRuntimeProfiles()
            profile.id
        }
    }

    private fun validateProfile(profile: FrpProfile) {
        require(!profile.storageError) { "CONFIG_DECRYPT_FAILED" }
        require(profile.id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid profile ID" }
        require(profile.name.isNotBlank()) { "PROFILE_NAME_REQUIRED" }
        require(profile.name.length <= 200) { "PROFILE_NAME_TOO_LONG" }
        require(profile.toml.toByteArray().size <= 1024 * 1024) { "CONFIG_TOO_LARGE" }
    }

    private fun encodeProfile(profile: FrpProfile): FrpProfileEntity =
        profile.toEntity().copy(toml = cipher.encrypt(profile.toml.toByteArray(), profile.id))

    private fun decodeProfile(entity: FrpProfileEntity): FrpProfile = try {
        entity.toModel().copy(toml = cipher.decrypt(entity.toml, entity.id).toString(Charsets.UTF_8))
    } catch (_: GeneralSecurityException) {
        entity.toModel().copy(toml = "", storageError = true)
    } catch (_: IllegalArgumentException) {
        entity.toModel().copy(toml = "", storageError = true)
    }

    private suspend fun prepareProfile(profile: FrpProfile, files: Map<String, ByteArray>? = null): FrpProfile {
        check(!profile.storageError) { "CONFIG_DECRYPT_FAILED" }
        val settings = settings.first()
        return profile.copy(
            files = files ?: readCertificates(profile.id),
            retry = settings.autoRetryEnabled,
            networkReconnect = settings.rootMode && settings.networkReconnectEnabled,
            autoStart = profile.autoStart && settings.bootStartEnabled,
        )
    }

    private suspend fun syncRuntimeProfiles() {
        ensureRuntimeReady()
        val profiles = prepareStoredProfiles()
        val result = runtimeManager.configure(profiles)
        check(result.isSuccess) { "CONFIG_SAVED_SYNC_FAILED: ${result.message}" }
        if (settings.first().runtimeSyncPending) settingsStore.setRuntimeSyncPending(false)
    }

    private suspend fun markRuntimeDirty() {
        if (settings.first().rootMode) settingsStore.setRuntimeSyncPending(true)
    }

    private suspend fun prepareStoredProfiles(): List<FrpProfile> =
        dao.getProfiles().map(::decodeProfile).filterNot { it.storageError }.mapNotNull { profile ->
            try { prepareProfile(profile) }
            catch (e: Exception) {
                if (e !is GeneralSecurityException && e !is IllegalArgumentException) throw e
                writeRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, "CONFIG_DECRYPT_FAILED", false)
                null
            }
        }

    suspend fun deleteProfile(id: String): FrpResult = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            deleteProfileLocked(id).also { if (it.isSuccess) syncRuntimeProfiles() }
        }
    }

    private suspend fun deleteProfileLocked(id: String): FrpResult {
        ensureRuntimeReady()
        val profile = dao.getProfile(id)?.let(::decodeProfile)
        val state = dao.getRuntimeStates().firstOrNull { it.id == id }
        if (profile != null && (state?.desiredRunning == true ||
                state?.state == FrpInstanceStatus.Running || state?.state == FrpInstanceStatus.Stopping)) {
            val stopped = stopLocked(profile)
            if (!stopped.isSuccess) return stopped
        }
        val deleted = runtimeManager.delete(id)
        if (!deleted.isSuccess) return deleted
        markRuntimeDirty()
        if (settings.first().tileProfileId == id) settingsStore.setTileProfileId("")
        dao.deleteConfiguration(id)
        return FrpResult(code = null, message = "")
    }

    suspend fun applyProfileBatch(
        profiles: List<FrpProfile>,
        action: ProfileBatchAction,
        onProgress: (Int) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): ProfileBatchResult = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        val targets = profiles.distinctBy { it.id }
        val succeeded = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val failures = mutableMapOf<String, String>()
        for (target in targets) {
            if (shouldCancel()) break
            try {
                val current = dao.getProfile(target.id)?.let(::decodeProfile)
                if (current == null) skipped += target.id
                else {
                    checkRevision(target.id, target.updatedAt)
                    if (action == ProfileBatchAction.Delete) {
                        val result = deleteProfileLocked(target.id)
                        check(result.isSuccess) { result.message }
                        succeeded += target.id
                    } else {
                        val enabled = action == ProfileBatchAction.EnableAutoStart
                        if (current.autoStart == enabled) skipped += target.id
                        else {
                            writeProfile(current.copy(autoStart = enabled))
                            succeeded += target.id
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures[target.id] = e.message ?: e.javaClass.simpleName }
            onProgress(succeeded.size + skipped.size + failures.size)
        }
        var syncError: String? = null
        if (succeeded.isNotEmpty()) {
            try { syncRuntimeProfiles() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { syncError = e.message ?: e.javaClass.simpleName }
        }
        ProfileBatchResult(succeeded, skipped, failures,
            targets.map { it.id }.toSet() - succeeded - skipped - failures.keys, syncError)
    } }

    suspend fun getProfile(id: String, includeFiles: Boolean = false): FrpProfile? = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            val profile = dao.getProfile(id)?.let(::decodeProfile) ?: return@withLock null
            if (!includeFiles || profile.storageError) return@withLock profile
            try { profile.copy(files = readCertificates(id)) }
            catch (_: GeneralSecurityException) { profile.copy(storageError = true) }
            catch (_: IllegalArgumentException) { profile.copy(storageError = true) }
        }
    }

    suspend fun getAutoStartProfiles(): List<FrpProfile> = withContext(Dispatchers.IO) {
        dao.getAutoStartProfiles().map { decodeProfile(it) }.filterNot { it.storageError }
    }

    suspend fun getDesiredRunningProfiles(): List<FrpProfile> = withContext(Dispatchers.IO) {
        ensureRuntimeReady()
        val ids = dao.getDesiredRunningStates().map { it.id }.toSet()
        ids.mapNotNull { id -> dao.getProfile(id)?.let(::decodeProfile) }
    }

    suspend fun shouldReconnectOnNetworkRecovery(): Boolean =
        settings.first().networkReconnectEnabled

    suspend fun shouldAutoRetryFailures(): Boolean =
        settings.first().autoRetryEnabled

    fun validateToml(toml: String): FrpResult = runtimeManager.validateToml(toml)

    suspend fun requestRun(id: String): String? = withContext(Dispatchers.IO) {
        ensureRuntimeReady()
        runtimeOperationMutex.withLock { requestRunLocked(id) }
    }

    private suspend fun requestRunLocked(id: String): String? {
        val profile = dao.getProfile(id) ?: return null
        val previous = dao.getRuntimeStates().firstOrNull { it.id == id }
        val token = UUID.randomUUID().toString()
        dao.upsertRuntimeState(FrpRuntimeStateEntity(
            id, profile.type, previous?.state ?: FrpInstanceStatus.Stopped, null, true, token,
        ))
        return token
    }

    suspend fun canRecover(id: String, token: String, reason: RecoveryReason): Boolean =
        withContext(Dispatchers.IO) {
            ensureRuntimeReady()
            val settings = settings.first()
            !settings.rootMode && when (reason) {
                RecoveryReason.AutoRetry -> settings.autoRetryEnabled
                RecoveryReason.Network -> settings.networkReconnectEnabled
            } && dao.getRuntimeStates().any { it.id == id && it.desiredRunning && it.runToken == token } &&
                dao.getProfile(id) != null
        }

    suspend fun runProfile(id: String, token: String? = null, restart: Boolean = false, reason: RecoveryReason? = null): FrpResult? {
        val recovered = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
            ensureRuntimeReady()
            val runToken = token ?: requestRunLocked(id) ?: return@withLock null
            val profile = dao.getProfile(id)?.let(::decodeProfile) ?: return@withLock null
            val current = dao.getRuntimeStates().firstOrNull { it.id == id }
            if (current?.desiredRunning != true || current.runToken != runToken) return@withLock null
            if (reason != null && !canRecover(id, runToken, reason)) return@withLock null
            profile to if (restart) reloadLocked(profile) else startLocked(profile)
        } } ?: return null
        appendLifecycleLog(recovered.first.id, recovered.first.type, recovered.second, if (reason == null) "start" else "recover")
        return recovered.second
    }

    suspend fun restoreDesiredProfiles(): Int {
        ensureRuntimeReady()
        val started = runtimeOperationMutex.withLock {
            syncRuntimeStatesFromNative()
            val profiles = dao.getDesiredRunningStates().mapNotNull { dao.getProfile(it.id)?.let(::decodeProfile) }
            profiles.map { profile ->
                val result = startLocked(profile)
                profile to result
            }
        }
        started.forEach { (profile, result) ->
            appendLifecycleLog(profile.id, profile.type, result, "restore")
        }
        return started.size
    }

    suspend fun saveAndRestart(profile: FrpProfile, expectedRevision: Long? = null, files: Map<String, ByteArray>? = null): Pair<FrpProfile, Boolean> = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            checkRevision(profile.id, expectedRevision)
            validateProfile(profile)
            val validation = validateToml(profile.toml)
            require(validation.isSuccess) { validation.message }
            val certificates = files?.let { encodeCertificates(profile.id, it) }
            val reloaded = dao.getRuntimeStates().any { it.id == profile.id && it.state == FrpInstanceStatus.Running }
            val result = if (reloaded) reloadLocked(profile, files) else FrpResult(null, "")
            check(result.isSuccess) { result.message }
            val stored = writeProfile(profile, certificates)
            syncRuntimeProfiles()
            stored to reloaded
        }
    }

    suspend fun stop(profile: FrpProfile): FrpResult {
        val result = runtimeOperationMutex.withLock {
            stopLocked(profile)
        }
        appendLifecycleLog(profile.id, profile.type, result, "stop")
        return result
    }

    suspend fun stopAll(): FrpResult {
        val result = runtimeOperationMutex.withLock {
            ensureRuntimeReady()
            dao.getRuntimeStates().forEach { state ->
                val nextState =
                    if (state.state == FrpInstanceStatus.Running || state.state == FrpInstanceStatus.Stopping) {
                        FrpInstanceStatus.Stopping
                    } else {
                        state.state
                    }
                dao.upsertRuntimeState(
                    state.copy(state = nextState, lastError = null, desiredRunning = false, runToken = UUID.randomUUID().toString()),
                )
            }
            settingsStore.setPendingStart(false)
            runtimeManager.stopAll().also {
                syncRuntimeStatesFromNative()
            }
        }
        appendLog(FrpLog("", "frp", if (result.isSuccess) "info" else "error", "stopAll ${result.message}", System.currentTimeMillis()))
        return result
    }

    suspend fun syncRuntimeStates(): List<FrpRuntimeState> = withContext(Dispatchers.IO) {
        ensureRuntimeReady()
        runtimeOperationMutex.withLock {
            if (runtimeManager.rootMode && settings.first().runtimeSyncPending) {
                val result = runtimeManager.configure(prepareStoredProfiles())
                if (result.isSuccess) settingsStore.setRuntimeSyncPending(false)
            }
            syncRuntimeStatesFromNative()
        }
    }

    private suspend fun syncRuntimeStatesFromNative(): List<FrpRuntimeState> {
        val query = runtimeManager.listInstances()
        if (query is FrpRuntimeQueryResult.Failure) {
            if (lastQueryError != query.message) appendLog(FrpLog("", "frp", "error", query.message, System.currentTimeMillis()))
            dao.getRuntimeStates().filter {
                it.state in setOf(FrpInstanceStatus.Running, FrpInstanceStatus.Stopping) && it.lastError != query.message
            }.forEach { dao.upsertRuntimeState(it.copy(lastError = query.message)) }
            lastQueryError = query.message
            return emptyList()
        }
        lastQueryError = null
        val states = (query as FrpRuntimeQueryResult.Success).states
        val nativeIds = states.map { it.id }.toSet()
        val existingById = dao.getRuntimeStates().associateBy { it.id }
        startedRunTokens.entries.removeAll { (id, token) -> existingById[id]?.runToken != token }
        existingById.values
            .filter { it.id !in nativeIds && it.state in setOf(FrpInstanceStatus.Running, FrpInstanceStatus.Stopping) }
            .forEach { stale ->
                dao.upsertRuntimeState(stale.copy(state = FrpInstanceStatus.Stopped, lastError = null))
            }
        states.forEach { observed ->
            val existing = existingById[observed.id]
            val updated = FrpRuntimeStateEntity(
                    id = observed.id,
                    type = observed.type,
                    state = observed.state,
                    lastError = observed.lastError,
                    desiredRunning = when {
                        runtimeManager.rootMode -> observed.desiredRunning
                        observed.state == FrpInstanceStatus.Failed && !observed.desiredRunning &&
                            startedRunTokens[observed.id] == existing?.runToken -> false
                        else -> existing?.desiredRunning ?: false
                    },
                    runToken = existing?.runToken ?: "",
                )
            if (updated != existing) dao.upsertRuntimeState(updated)
        }
        return states
    }

    private suspend fun startLocked(profile: FrpProfile): FrpResult = withContext(Dispatchers.IO) {
        ensureRuntimeReady()
        writeRuntimeState(
            id = profile.id,
            type = profile.type,
            state = dao.getRuntimeStates().firstOrNull { it.id == profile.id }?.state
                ?: FrpInstanceStatus.Stopped,
            lastError = null,
            desiredRunning = true,
        )
        val tlsFilesReady = validateManagedTlsFiles(profile)
        val result = try {
            if (tlsFilesReady.isSuccess) runtimeManager.start(prepareProfile(profile)) else tlsFilesReady
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            FrpResult("CONFIG_UNAVAILABLE", "CONFIG_UNAVAILABLE: ${e.message}")
        }
        persistLifecycleState(profile, result)
        result
    }

    private suspend fun stopLocked(profile: FrpProfile): FrpResult {
        ensureRuntimeReady()
        writeRuntimeState(
            id = profile.id,
            type = profile.type,
            state = FrpInstanceStatus.Stopping,
            lastError = null,
            desiredRunning = false,
            runToken = UUID.randomUUID().toString(),
        )
        return runtimeManager.stop(profile.id).also {
            syncRuntimeStatesFromNative()
            if (dao.getDesiredRunningStates().isEmpty()) settingsStore.setPendingStart(false)
        }
    }

    private suspend fun reloadLocked(profile: FrpProfile, files: Map<String, ByteArray>? = null): FrpResult {
        ensureRuntimeReady()
        val tlsFilesReady = validateManagedTlsFiles(profile, files?.keys)
        val result = try {
            if (tlsFilesReady.isSuccess) runtimeManager.reload(prepareProfile(profile, files)) else tlsFilesReady
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            FrpResult("CONFIG_UNAVAILABLE", "CONFIG_UNAVAILABLE: ${e.message}")
        }
        persistLifecycleState(profile, result)
        return result
    }

    private suspend fun persistLifecycleState(
        profile: FrpProfile,
        result: FrpResult,
    ) {
        val current = dao.getRuntimeStates().firstOrNull { it.id == profile.id }
        when {
            result.isSuccess -> {
                writeRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null, desiredRunning = true)
                // Only a failure from this run may cancel its intent; queued starts use a new token.
                current?.let { startedRunTokens[profile.id] = it.runToken }
            }
            !result.isRetryable -> {
                if (current?.state != FrpInstanceStatus.Running) {
                    writeRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, result.message, desiredRunning = false)
                }
            }
            else -> {
                writeRuntimeState(
                    profile.id,
                    profile.type,
                    FrpInstanceStatus.Failed,
                    result.message.ifBlank { null },
                    desiredRunning = true,
                )
            }
        }
    }

    private suspend fun writeRuntimeState(
        id: String,
        type: FrpType,
        state: FrpInstanceStatus,
        lastError: String?,
        desiredRunning: Boolean? = null,
        runToken: String? = null,
    ) {
        val existing = dao.getRuntimeStates().firstOrNull { it.id == id }
        dao.upsertRuntimeState(
            FrpRuntimeStateEntity(
                id = id,
                type = type,
                state = state,
                lastError = lastError,
                desiredRunning = desiredRunning ?: existing?.desiredRunning ?: false,
                runToken = runToken ?: existing?.runToken ?: "",
            ),
        )
    }

    suspend fun pruneLogs(retentionDays: Int) {
        val olderThan = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        runtimeOperationMutex.withLock {
            ensureRuntimeReady()
            runtimeManager.pruneLogCache(olderThan)
        }
        dao.deleteLogsOlderThan(olderThan)
    }

    suspend fun clearLogs() = runtimeOperationMutex.withLock {
        ensureRuntimeReady()
        val result = runtimeManager.pruneLogCache(Long.MAX_VALUE)
        check(result.isSuccess) { result.message }
        try {
            logConsumerJob?.cancelAndJoin()
            while (logChannel.tryReceive().isSuccess) { /* Drain pending logs before clearing. */ }
            logWriteMutex.withLock {
                logsSinceTrim = 0
                dao.clearLogs()
            }
        } finally {
            startLogConsumer()
        }
    }

    suspend fun setBootStartEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        markRuntimeDirty()
        settingsStore.setBootStartEnabled(enabled)
        syncRuntimeProfiles()
    } }
    suspend fun setNetworkReconnectEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        markRuntimeDirty()
        settingsStore.setNetworkReconnectEnabled(enabled)
        syncRuntimeProfiles()
    } }
    suspend fun setAutoRetryEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        markRuntimeDirty()
        settingsStore.setAutoRetryEnabled(enabled)
        syncRuntimeProfiles()
    } }
    suspend fun setScreenOffKeepAliveEnabled(enabled: Boolean) = settingsStore.setScreenOffKeepAliveEnabled(enabled)
    suspend fun setLogRetentionDays(days: Int) {
        settingsStore.setLogRetentionDays(days)
        pruneLogs(days)
    }
    suspend fun setThemeSeedColor(color: Int) = settingsStore.setThemeSeedColor(color)
    suspend fun setLanguageMode(mode: LanguageMode) = settingsStore.setLanguageMode(mode)
    suspend fun setPendingStart(pending: Boolean) = runtimeOperationMutex.withLock {
        settingsStore.setPendingStart(pending && dao.getDesiredRunningStates().isNotEmpty())
    }

    private suspend fun appendLifecycleLog(id: String, type: FrpType, result: FrpResult, action: String) {
        val level = if (result.isSuccess) "info" else "error"
        val message = if (result.message.isBlank()) "$action success" else "$action ${result.message}"
        appendLog(FrpLog(id, type.name.lowercase(), level, message, System.currentTimeMillis()))
    }

    suspend fun ensureRuntimeReady() =
        initMutex.withLock {
            if (initialized) return@withLock

            val settings = settings.first()
            runtimeManager.rootMode = settings.rootMode
            runtimeManager.setLogSink(
                FrpLogSink { log ->
                    logChannel.trySend(log)
                },
            )
            val bootCount = runtimeManager.bootCount
            if (bootCount >= 0 && bootCount != settings.runtimeBootCount) {
                if (settings.runtimeBootCount >= 0) {
                    dao.getRuntimeStates().forEach { state ->
                        dao.upsertRuntimeState(if (settings.rootMode) {
                            state.copy(runToken = UUID.randomUUID().toString())
                        } else {
                            state.copy(state = FrpInstanceStatus.Stopped, lastError = null,
                                desiredRunning = false, runToken = UUID.randomUUID().toString())
                        })
                    }
                    if (!settings.rootMode) {
                        runtimeManager.pruneLogCache(Long.MAX_VALUE)
                        if (settings.bootStartEnabled) dao.getAutoStartProfiles().filterNot { decodeProfile(it).storageError }.forEach { profile ->
                            dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type,
                                FrpInstanceStatus.Stopped, null, true, UUID.randomUUID().toString()))
                        }
                    }
                    settingsStore.setPendingStart(false)
                }
                settingsStore.setRuntimeBootCount(bootCount)
            }
            initialized = true
        }

    private fun appendLog(log: FrpLog) {
        logChannel.trySend(log)
    }

    private fun startLogConsumer() {
        if (logConsumerJob?.isActive == true) return
        logConsumerJob = scope.launch {
            consumeLogs()
        }
    }

    private suspend fun consumeLogs() {
        while (true) {
            val batch = ArrayList<FrpLog>(logBatchSize)
            batch += logChannel.receive()
            if (batch.size < logBatchSize) {
                withTimeoutOrNull(logFlushDelayMs) {
                    while (batch.size < logBatchSize) {
                        batch += logChannel.receive()
                    }
                }
            }

            persistLogs(batch)
        }
    }

    private suspend fun persistLogs(logs: List<FrpLog>) {
        val safeLogs = logs.map { log -> log.copy(message = redact(log.message)).toEntity() }
        logWriteMutex.withLock {
            dao.insertLogs(safeLogs)
            logsSinceTrim += logs.size
            if (logsSinceTrim >= logTrimInterval) {
                dao.trimLogs(logMaxCount)
                logsSinceTrim %= logTrimInterval
            }
        }
    }

    private val secretAssignment = Regex("(?i)(token|password|secret|authorization)\\s*=\\s*[^\\s,;]+")
    private val secretHeader = Regex("(?i)(token|password|secret|authorization):\\s*[^\\s,;]+")
    private fun redact(message: String): String =
        message.replace(secretAssignment, "\$1=***").replace(secretHeader, "\$1: ***")

    private suspend fun validateManagedTlsFiles(profile: FrpProfile, fileNames: Set<String>? = null): FrpResult {
        if (profile.storageError) return FrpResult("CONFIG_DECRYPT_FAILED", "CONFIG_DECRYPT_FAILED")
        val files = (fileNames ?: dao.getCertificates(profile.id).map { it.name }).map { "frpmobile://$it" }.toSet()
        val missing = runtimeManager.tlsFilePaths(profile.toml).firstOrNull {
            it.startsWith("frpmobile://") && it !in files
        }
        return if (missing == null) FrpResult(null, "")
        else FrpResult("TLS_FILE_MISSING", "TLS_FILE_MISSING: $missing")
    }

    suspend fun setThemeMode(mode: ThemeMode) = settingsStore.setThemeMode(mode)

    suspend fun setLaunchStartEnabled(enabled: Boolean) = settingsStore.setLaunchStartEnabled(enabled)
    suspend fun setExcludeFromRecents(enabled: Boolean) = settingsStore.setExcludeFromRecents(enabled)
    suspend fun setLiveUpdatesEnabled(enabled: Boolean) = settingsStore.setLiveUpdatesEnabled(enabled)
    suspend fun setExternalReadEnabled(enabled: Boolean) = runtimeOperationMutex.withLock { settingsStore.setExternalReadEnabled(enabled) }
    suspend fun setExternalWriteEnabled(enabled: Boolean) = runtimeOperationMutex.withLock { settingsStore.setExternalWriteEnabled(enabled) }

    suspend fun setAutomationEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        val token = if (enabled) {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            cipher.encrypt(kotlin.io.encoding.Base64.UrlSafe.encode(bytes).toByteArray(), "automation")
        } else ""
        settingsStore.setAutomationToken(token)
    }

    suspend fun automationToken(): String = withContext(Dispatchers.IO) {
        val encrypted = settings.first().automationToken
        if (encrypted.isEmpty()) "" else cipher.decrypt(encrypted, "automation").toString(Charsets.UTF_8)
    }

    suspend fun authorizeAutomation(token: String): Boolean {
        if (token.length !in 32..128) return false
        val expected = automationToken()
        return expected.isNotEmpty() && java.security.MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())
    }

    suspend fun setTileProfileId(id: String) = runtimeOperationMutex.withLock {
        require(id.isEmpty() || dao.getProfile(id) != null) { "Profile not found" }
        settingsStore.setTileProfileId(id)
    }

    suspend fun moduleStatus(): ModuleStatus = runtimeManager.module("status")

    suspend fun exportBackup(): ByteArray = withContext(Dispatchers.IO) { runtimeOperationMutex.withLock {
        val profiles = dao.getProfiles().map(::decodeProfile).map { profile ->
            check(!profile.storageError) { "CONFIG_DECRYPT_FAILED: ${profile.name}" }
            profile.copy(files = dao.getCertificates(profile.id).associate {
                it.name to cipher.decrypt(it.content, "${it.profileId}/${it.name}")
            })
        }
        ConfigBackupFormat.encode(ConfigBackup(profiles, settings.first()))
    } }

    suspend fun readBackup(input: InputStream): ConfigBackup = withContext(Dispatchers.IO) {
        input.use { ConfigBackupFormat.decode(it.readLimited(ConfigBackupFormat.MAX_BYTES)) }
    }

    suspend fun restoreBackup(backup: ConfigBackup, replace: Boolean): FrpResult = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            val encoded = backup.profiles.map { encodeProfile(it.copy(updatedAt = nextRevision(it.id))) }
            val certificates = backup.profiles.flatMap { profile ->
                profile.files.map { (name, bytes) ->
                    CertificateEntity(profile.id, name, cipher.encrypt(bytes, "${profile.id}/$name"))
                }
            }
            ensureRuntimeReady()
            val stopped = runtimeManager.stopAll()
            if (!stopped.isSuccess) return@withLock stopped
            dao.restoreProfiles(encoded, certificates, replace, backup.settings.copy(
                rootMode = runtimeManager.rootMode,
                automationToken = settings.first().automationToken,
                externalReadEnabled = settings.first().externalReadEnabled,
                externalWriteEnabled = settings.first().externalWriteEnabled,
                runtimeSyncPending = runtimeManager.rootMode,
                runtimeBootCount = settings.first().runtimeBootCount,
            ))
            syncRuntimeProfiles()
            FrpResult(null, "")
        }
    }

    suspend fun manageModule(action: String): ModuleStatus = withContext(Dispatchers.IO) {
        runtimeOperationMutex.withLock {
            ensureRuntimeReady()
            val currentMode = runtimeManager.rootMode
            if (action == "install" || action == "start") {
                val status = runtimeManager.module("status")
                if (status.error != null) return@withLock status
                val prepared = prepareStoredProfiles()
                val desired = dao.getDesiredRunningStates().map { it.id }.toSet()
                dao.getRuntimeStates().forEach { dao.upsertRuntimeState(it.copy(runToken = UUID.randomUUID().toString())) }
                if (!currentMode || status.running) {
                    val stopped = runtimeManager.stopAll()
                    if (!stopped.isSuccess) return@withLock status.copy(error = stopped.message)
                }
                val result = runtimeManager.module(action)
                if (result.error != null) {
                    if (!currentMode) prepared.filter { it.id in desired }.forEach { runtimeManager.start(it) }
                    return@withLock result
                }
                runtimeManager.rootMode = true
                settingsStore.setRuntimeSyncPending(true)
                settingsStore.setRootMode(true)
                val reconnect = settings.first().networkReconnectEnabled
                val rootProfiles = prepared.map { it.copy(networkReconnect = reconnect) }
                val configured = runtimeManager.configure(rootProfiles)
                if (!configured.isSuccess) return@withLock result.copy(error = configured.message)
                settingsStore.setRuntimeSyncPending(false)
                val errors = rootProfiles.filter { it.id in desired }.map { runtimeManager.start(it) }.filterNot { it.isSuccess }
                syncRuntimeStatesFromNative()
                val observed = runtimeManager.module("status")
                return@withLock observed.copy(
                    error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.message } ?: observed.error,
                )
            }
            val result = runtimeManager.module(action)
            if (result.error == null && currentMode) {
                runtimeManager.rootMode = false
                settingsStore.setRootMode(false)
                settingsStore.setRuntimeSyncPending(false)
                dao.getRuntimeStates().forEach { dao.upsertRuntimeState(it.copy(
                    state = FrpInstanceStatus.Stopped, desiredRunning = false, runToken = UUID.randomUUID().toString(),
                )) }
            }
            result
        }
    }

}

fun InputStream.readLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size() + size <= limit) { "File exceeds size limit" }
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}
