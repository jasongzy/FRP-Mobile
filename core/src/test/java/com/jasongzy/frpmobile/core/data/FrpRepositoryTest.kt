package com.jasongzy.frpmobile.core.data

import com.jasongzy.frpmobile.core.frp.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
class FrpRepositoryTest {
    @Test fun networkPolicyIsSynchronizedOnlyToTheRootBackend() = runTest {
        for (root in listOf(false, true)) {
            val runtime = FakeRuntime()
            val settings = MemorySettings(FrpSettings(rootMode = root))
            val repository = FrpRepository(MemoryDao(), settings, runtime, cipher, backgroundScope)
            repository.createProfile(profile)
            repository.setNetworkReconnectEnabled(true)
            assertEquals(root, runtime.configured.single().networkReconnect)
            repository.setNetworkReconnectEnabled(false)
            assertFalse(runtime.configured.single().networkReconnect)
            assertFalse(settings.settings.first().runtimeSyncPending)
        }
    }

    @Test fun clearingLogsPropagatesFailuresAndKeepsTheConsumerRunning() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.ensureRuntimeReady()
        fun emit(message: String) = runtime.capturedLogSink!!.onLog(FrpLog("test", "client", "info", message, 1))
        emit("before")
        advanceTimeBy(1000)
        runCurrent()
        runtime.pruneResult = FrpResult("OFFLINE", "Runtime unavailable")
        try { repository.clearLogs(); fail("Runtime cleanup failure must be reported") }
        catch (_: IllegalStateException) { }
        assertEquals(listOf("before"), dao.recordedLogs.map { it.message })
        runtime.pruneResult = FrpResult(null, "")
        dao.clearFailure = true
        try { repository.clearLogs(); fail("Storage failure must be reported") }
        catch (_: IllegalStateException) { }
        emit("after failure")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("before", "after failure"), dao.recordedLogs.map { it.message })
        dao.clearFailure = false
        repository.clearLogs()
        assertTrue(dao.recordedLogs.isEmpty())
        emit("after clear")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("after clear"), dao.recordedLogs.map { it.message })
    }

    @Test fun externalAccessIsOptInRevocableAndPreservesConcurrentEdits() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.createProfile(profile)
        try { repository.readExternalProfiles(null); fail("Read must be opt-in") }
        catch (_: SecurityException) { }
        try { repository.writeExternalProfile(null, "New", FrpType.Server, "bindPort = 7000", null); fail("Write must be opt-in") }
        catch (_: SecurityException) { }
        repository.setExternalReadEnabled(true)
        repository.setExternalWriteEnabled(true)
        val original = repository.readExternalProfiles(profile.id).single()
        repository.writeExternalProfile(profile.id, null, null, "serverAddr = 'updated'", original.updatedAt)
        val updated = repository.readExternalProfiles(profile.id).single()
        assertTrue(updated.updatedAt > original.updatedAt)
        assertEquals("serverAddr = 'updated'", updated.toml)
        try {
            repository.writeExternalProfile(profile.id, null, null, "serverAddr = 'stale'", original.updatedAt)
            fail("Stale writer must fail")
        } catch (_: IllegalArgumentException) { }
        runtime.validationResult = FrpResult("INVALID_TOML", "Invalid TOML")
        try {
            repository.writeExternalProfile(profile.id, null, null, "invalid", updated.updatedAt)
            fail("Invalid TOML must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(updated, repository.readExternalProfiles(profile.id).single())
        try {
            repository.upsertProfile(original.copy(name = "Stale editor"), original.updatedAt)
            fail("Stale in-app edits must fail")
        } catch (_: IllegalArgumentException) { }
        try {
            repository.saveAndRestart(original, original.updatedAt)
            fail("Stale reload must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(updated, repository.getProfile(profile.id))
        repository.setExternalReadEnabled(false)
        repository.setExternalWriteEnabled(false)
        try { repository.readExternalProfiles(profile.id); fail("Revoked read") }
        catch (_: SecurityException) { }
        try { repository.writeExternalProfile(profile.id, null, null, updated.toml, updated.updatedAt); fail("Revoked write") }
        catch (_: SecurityException) { }
    }

    @Test fun externalCreationAndBackupsKeepAccessAndRunIntentSeparate() = runTest {
        for (rootMode in listOf(false, true)) {
            val dao = MemoryDao()
            val runtime = FakeRuntime()
            val repository = FrpRepository(dao, MemorySettings(FrpSettings(rootMode = rootMode)), runtime, cipher, backgroundScope)
            repository.setExternalWriteEnabled(true)
            val id = repository.writeExternalProfile(null, "Server", FrpType.Server, "bindPort = 7000", null)
            assertFalse(repository.getProfile(id)!!.autoStart)
            assertTrue(runtime.started.isEmpty())
            assertNotEquals("bindPort = 7000", dao.getProfile(id)!!.toml)
            assertEquals(rootMode, repository.settings.first().runtimeSyncPending)
            repository.setLaunchStartEnabled(true)
            repository.setExcludeFromRecents(true)
            val backup = ConfigBackupFormat.decode(repository.exportBackup())
            assertTrue(backup.settings.launchStartEnabled && backup.settings.excludeFromRecents)
            assertFalse(backup.settings.externalReadEnabled || backup.settings.externalWriteEnabled)
        }
    }

    @Test fun firstSaveStoresConfigurationAndAttachmentsWhileRuntimeIsUnavailable() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime().apply { configureResult = FrpResult("OFFLINE", "Runtime unavailable") }
        val settings = MemorySettings(FrpSettings(rootMode = true))
        val repository = FrpRepository(dao, settings, runtime, cipher, backgroundScope)
        val certificate = "private".toByteArray()
        val stored = repository.createProfile(profile.copy(files = mapOf("key.pem" to certificate)))
        assertEquals(stored, repository.getProfile(profile.id))
        assertEquals(profile.toml, repository.getProfile(profile.id)?.toml)
        assertNotEquals("private", dao.getCertificates(profile.id).single().content)
        assertArrayEquals(certificate, repository.getProfile(profile.id, includeFiles = true)!!.files["key.pem"])
        assertTrue(runtime.configured.isEmpty())
        assertTrue(runtime.started.isEmpty())
        assertTrue(settings.settings.first().runtimeSyncPending)
        try { repository.createProfile(profile.copy(toml = "replacement")); fail("Expected duplicate rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(profile.toml, repository.getProfile(profile.id)?.toml)
        assertEquals(1, dao.getCertificates(profile.id).size)
        runtime.configureResult = FrpResult(null, "")
        repository.syncRuntimeStates()
        assertEquals(profile.id, runtime.configured.single().id)
        assertArrayEquals(certificate, runtime.configured.single().files["key.pem"])
        assertFalse(settings.settings.first().runtimeSyncPending)
    }

    @Test fun invalidDraftLeavesConfigurationAndAttachmentsUnwritten() = runTest {
        val dao = MemoryDao()
        val settings = MemorySettings(FrpSettings(rootMode = true))
        val repository = FrpRepository(dao, settings, FakeRuntime(), cipher, backgroundScope)
        val validFile = mapOf("cert.pem" to "certificate".toByteArray())
        val drafts = listOf(
            profile.copy(name = "", files = validFile) to "PROFILE_NAME_REQUIRED",
            profile.copy(name = "a".repeat(201)) to "PROFILE_NAME_TOO_LONG",
            profile.copy(toml = "a".repeat(1024 * 1024 + 1)) to "CONFIG_TOO_LARGE",
            profile.copy(files = validFile + ("../key.pem" to "key".toByteArray())) to "Invalid certificate name",
            profile.copy(files = validFile + ("key.pem" to byteArrayOf())) to "Invalid certificate size",
            profile.copy(files = validFile + ("key.pem" to ByteArray(1024 * 1024 + 1))) to "Invalid certificate size",
        )
        drafts.forEach { (draft, message) ->
            try { repository.createProfile(draft); fail("Expected validation failure") }
            catch (e: IllegalArgumentException) { assertEquals(message, e.message) }
            assertNull(dao.getProfile(draft.id))
            assertTrue(dao.getCertificates(draft.id).isEmpty())
            assertFalse(settings.settings.first().runtimeSyncPending)
        }
    }

    @Test fun restoreReconcilesInterruptedStopsAndPreservesRunIntent() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.upsertProfile(profile)
        dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, FrpInstanceStatus.Stopping, "old error", false))
        assertEquals(0, repository.restoreDesiredProfiles())
        assertEquals(FrpInstanceStatus.Stopped, dao.getRuntimeStates().single().state)
        assertNull(dao.getRuntimeStates().single().lastError)
        assertFalse(dao.getRuntimeStates().single().desiredRunning)
        assertTrue(runtime.started.isEmpty())
        dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        assertEquals(1, repository.restoreDesiredProfiles())
        assertEquals(profile.id, runtime.started.single().id)
    }

    @Test fun mergeRestoreKeepsProfilesAndStopsTheirRunIntent() = runTest {
        val dao = MemoryDao()
        val repository = repository(dao, FakeRuntime())
        repository.upsertProfile(profile)
        dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        assertTrue(repository.restoreBackup(ConfigBackup(emptyList(), FrpSettings()), replace = false).isSuccess)
        assertNotNull(repository.getProfile(profile.id))
        assertFalse(dao.getRuntimeStates().single().desiredRunning)
        assertEquals(FrpInstanceStatus.Stopped, dao.getRuntimeStates().single().state)
    }

    @Test fun restoredConfigurationRejectsEditsFromBeforeRestore() = runTest {
        for (replace in listOf(false, true)) {
            val repository = repository(MemoryDao(), FakeRuntime())
            val original = repository.createProfile(profile)
            val restored = original.copy(toml = "serverAddr = 'restored'")
            assertTrue(repository.restoreBackup(ConfigBackup(listOf(restored), FrpSettings()), replace).isSuccess)
            val stored = repository.getProfile(profile.id)!!
            assertEquals(restored.toml, stored.toml)
            assertTrue(stored.updatedAt > original.updatedAt)
            try { repository.upsertProfile(original, original.updatedAt); fail("Pre-restore revision must be stale") }
            catch (_: IllegalArgumentException) { }
            assertEquals(stored, repository.getProfile(profile.id))
        }
    }
    @Test fun failedRootSyncIsRetriedFromPersistedSettings() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime().apply { configureResult = FrpResult("OFFLINE", "Daemon offline") }
        val settings = MemorySettings(FrpSettings(rootMode = true))
        val repository = FrpRepository(dao, settings, runtime, cipher, backgroundScope)
        try { repository.upsertProfile(profile); fail("Expected sync failure") }
        catch (_: IllegalStateException) { }
        assertNotNull(repository.getProfile(profile.id))
        assertTrue(settings.settings.first().runtimeSyncPending)
        runtime.configureResult = FrpResult(null, "")
        repository.syncRuntimeStates()
        assertFalse(settings.settings.first().runtimeSyncPending)
        assertEquals(profile.id, runtime.configured.single().id)
        assertTrue(runtime.started.isEmpty())
    }
    @Test fun automationRequiresCurrentTokenAndCanBeRevoked() = runTest {
        val repository = repository(MemoryDao(), FakeRuntime())
        assertFalse(repository.authorizeAutomation("a".repeat(44)))
        repository.setAutomationEnabled(true)
        val token = repository.automationToken()
        assertTrue(repository.authorizeAutomation(token))
        assertFalse(repository.authorizeAutomation("wrong-token"))
        repository.setAutomationEnabled(true)
        assertFalse(repository.authorizeAutomation(token))
        repository.setAutomationEnabled(false)
        assertFalse(repository.authorizeAutomation(repository.automationToken()))
    }

    @Test fun deletingTileProfileLeavesExportImportable() = runTest {
        val repository = repository(MemoryDao(), FakeRuntime())
        repository.upsertProfile(profile)
        repository.setTileProfileId(profile.id)
        assertTrue(repository.deleteProfile(profile.id).isSuccess)
        val backup = ConfigBackupFormat.decode(repository.exportBackup())
        assertEquals("", backup.settings.tileProfileId)
        assertTrue(backup.profiles.isEmpty())
    }
    private val profile = FrpProfile("test", "Client", FrpType.Client, "serverAddr = 'localhost'", false, 1)
    private val cipher = object : ConfigCipher {
        override fun encrypt(value: ByteArray, identity: String) = "$identity:${value.toString(Charsets.UTF_8)}"
        override fun decrypt(value: String, identity: String): ByteArray {
            if (!value.startsWith("$identity:")) throw AEADBadTagException()
            return value.removePrefix("$identity:").toByteArray()
        }
    }

    private fun TestScope.repository(dao: MemoryDao, runtime: FakeRuntime) = FrpRepository(
        dao = dao, settingsStore = MemorySettings(), runtimeManager = runtime, cipher = cipher, scope = backgroundScope,
    )

    @Test fun saveAndRestartValidatesBeforeReloadAndReturnsCommittedRevision() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        val original = repository.createProfile(profile)
        dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        for (name in listOf("", "x".repeat(201))) {
            try {
                repository.saveAndRestart(original.copy(name = name, toml = "serverAddr = 'changed'"), original.updatedAt)
                fail("Invalid metadata must fail before reload")
            } catch (_: IllegalArgumentException) { }
        }
        runtime.validationResult = FrpResult("INVALID_TOML", "Invalid TOML")
        try { repository.saveAndRestart(original.copy(toml = "bad"), original.updatedAt); fail("Invalid TOML must fail") }
        catch (_: IllegalArgumentException) { }
        assertTrue(runtime.reloaded.isEmpty())
        runtime.validationResult = FrpResult(null, "")
        runtime.reloadResult = FrpResult("INVALID_TOML", "Invalid replacement")
        try { repository.saveAndRestart(original.copy(toml = "bad"), original.updatedAt); fail("Failed reload must fail") }
        catch (_: IllegalStateException) { }
        assertEquals(original, repository.getProfile(profile.id))
        assertEquals(FrpInstanceStatus.Running, dao.getRuntimeStates().single().state)
        runtime.reloadResult = FrpResult(null, "")
        val (stored, reloaded) = repository.saveAndRestart(original.copy(toml = "serverAddr = 'changed'"), original.updatedAt)
        assertTrue(reloaded)
        assertEquals(stored, repository.getProfile(profile.id))
        assertTrue(stored.updatedAt > original.updatedAt)
        for (status in listOf(FrpInstanceStatus.Stopped, FrpInstanceStatus.Failed, FrpInstanceStatus.Stopping)) {
            dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, status, null, true))
            val current = repository.getProfile(profile.id)!!
            val reloadCount = runtime.reloaded.size
            val (_, restarted) = repository.saveAndRestart(current, current.updatedAt)
            assertFalse(restarted)
            assertEquals(reloadCount, runtime.reloaded.size)
        }
    }

    @Test fun failedStopPreservesProfileAndCertificates() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.upsertProfile(profile)
        repository.upsertProfile(profile, files = mapOf("key.pem" to "private".toByteArray()))
        dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        runtime.stopResult = FrpResult("STOP_FAILED", "Still running")
        assertFalse(repository.deleteProfile(profile.id).isSuccess)
        assertNotNull(repository.getProfile(profile.id))
        assertEquals(1, dao.getCertificates(profile.id).size)
    }

    @Test fun damagedEncryptedProfileDoesNotHideOtherProfiles() = runTest {
        val dao = MemoryDao()
        val repository = repository(dao, FakeRuntime())
        repository.upsertProfile(profile)
        dao.upsertProfile(profile.copy(id = "damaged").toEntity().copy(toml = "broken"))
        val profiles = repository.profiles.first().associateBy { it.id }
        assertEquals(profile.toml, profiles.getValue(profile.id).toml)
        assertTrue(profiles.getValue("damaged").storageError)
        assertEquals("broken", dao.getProfile("damaged")?.toml)
        dao.upsertCertificate(CertificateEntity(profile.id, "key.pem", "broken"))
        assertTrue(repository.getProfile(profile.id, includeFiles = true)!!.storageError)
        repository.upsertProfile(profile.copy(id = "another"))
        assertNotNull(repository.getProfile("another"))
        assertEquals("broken", dao.getCertificates(profile.id).single().content)
        assertEquals("CONFIG_DECRYPT_FAILED", dao.getRuntimeStates().single().lastError)
    }

    @Test fun editedCertificatesCommitWithRevisionAndReachReload() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.createProfile(profile.copy(files = mapOf("key.pem" to byteArrayOf(1))))
        val original = repository.getProfile(profile.id, includeFiles = true)!!
        val edited = original.copy(files = mapOf("key.pem" to byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1), repository.getProfile(profile.id, includeFiles = true)!!.files["key.pem"])
        dao.upsertRuntimeState(FrpRuntimeStateEntity(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        runtime.reloadResult = FrpResult("INVALID_TOML", "Invalid TLS")
        try { repository.saveAndRestart(edited, original.updatedAt, edited.files); fail("Failed reload must preserve files") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(byteArrayOf(1), repository.getProfile(profile.id, includeFiles = true)!!.files["key.pem"])
        runtime.reloadResult = FrpResult(null, "")
        val (saved, _) = repository.saveAndRestart(edited, original.updatedAt, edited.files)
        assertArrayEquals(byteArrayOf(2), runtime.reloaded.last().files["key.pem"])
        assertArrayEquals(byteArrayOf(2), repository.getProfile(profile.id, includeFiles = true)!!.files["key.pem"])
        try { repository.upsertProfile(original, original.updatedAt, emptyMap()); fail("Stale files must not overwrite saved files") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(2), repository.getProfile(profile.id, includeFiles = true)!!.files["key.pem"])
        repository.upsertProfile(saved.copy(name = "renamed"), saved.updatedAt)
        assertArrayEquals(byteArrayOf(2), repository.getProfile(profile.id, includeFiles = true)!!.files["key.pem"])
        val latest = repository.getProfile(profile.id)!!
        repository.upsertProfile(latest, latest.updatedAt, emptyMap())
        assertTrue(repository.getProfile(profile.id, includeFiles = true)!!.files.isEmpty())
    }

    @Test fun certificatesUseCipherAndResolveForRuntime() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.upsertProfile(profile)
        repository.upsertProfile(profile, files = mapOf("key.pem" to "private".toByteArray()))
        assertNotEquals("private", dao.getCertificates(profile.id).single().content)
        assertTrue(repository.runProfile(profile.id, repository.requestRun(profile.id)!!)!!.isSuccess)
        assertArrayEquals("private".toByteArray(), runtime.started.single().files["key.pem"])
    }

    @Test fun stoppedAndSupersededRequestsCannotRestartAProfile() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.createProfile(profile)
        val oldToken = repository.requestRun(profile.id)!!
        assertTrue(repository.canRecover(profile.id, oldToken, RecoveryReason.Network))
        repository.setPendingStart(true)
        repository.stop(profile)
        assertFalse(repository.settings.first().pendingStart)
        repository.setPendingStart(true)
        assertFalse(repository.settings.first().pendingStart)
        assertNull(repository.runProfile(profile.id, oldToken))
        assertFalse(repository.canRecover(profile.id, oldToken, RecoveryReason.Network))
        val currentToken = repository.requestRun(profile.id)!!
        assertNotEquals(oldToken, currentToken)
        assertNull(repository.runProfile(profile.id, oldToken, reason = RecoveryReason.Network))
        assertTrue(repository.runProfile(profile.id, currentToken)!!.isSuccess)
        repository.deleteProfile(profile.id)
        repository.createProfile(profile)
        repository.requestRun(profile.id)
        assertNull(repository.runProfile(profile.id, currentToken))
        assertEquals(1, runtime.started.size)
    }

    @Test fun recoveryChecksCurrentModeAndSwitchAtExecution() = runTest {
        val runtime = FakeRuntime()
        val settings = MemorySettings()
        val repository = FrpRepository(MemoryDao(), settings, runtime, cipher, backgroundScope)
        repository.createProfile(profile)
        val token = repository.requestRun(profile.id)!!
        settings.setNetworkReconnectEnabled(false)
        assertNull(repository.runProfile(profile.id, token, reason = RecoveryReason.Network))
        settings.setAutoRetryEnabled(true)
        settings.setRootMode(true)
        assertNull(repository.runProfile(profile.id, token, reason = RecoveryReason.AutoRetry))
        assertTrue(runtime.started.isEmpty())
    }

    @Test fun localTerminalFailureEndsRecoveryWithoutCancellingLaterRequests() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = repository(dao, runtime)
        repository.createProfile(profile)
        assertTrue(repository.runProfile(profile.id)!!.isSuccess)
        val previousToken = dao.getRuntimeStates().single().runToken
        runtime.states = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, "exit status 1", true))
        repository.syncRuntimeStates()
        assertTrue(repository.canRecover(profile.id, previousToken, RecoveryReason.Network))
        runtime.states = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Failed, "INVALID_TOML", false))
        repository.syncRuntimeStates()
        assertFalse(repository.canRecover(profile.id, previousToken, RecoveryReason.Network))
        val token = repository.requestRun(profile.id)!!
        repeat(2) { repository.syncRuntimeStates() }
        assertTrue(repository.canRecover(profile.id, token, RecoveryReason.Network))
        val reopened = FrpRepository(dao, MemorySettings(), runtime, cipher, backgroundScope)
        reopened.syncRuntimeStates()
        assertTrue(reopened.canRecover(profile.id, token, RecoveryReason.Network))
        repository.stop(profile)
        runtime.states = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        repository.syncRuntimeStates()
        assertFalse(repository.canRecover(profile.id, token, RecoveryReason.Network))
    }

    @Test fun rootStartKeepsRunIntentAndNativeCommandAtomicWithObservation() = runTest {
        val dao = MemoryDao()
        val runtime = FakeRuntime()
        val repository = FrpRepository(dao, MemorySettings(FrpSettings(rootMode = true)), runtime, cipher, backgroundScope)
        repository.createProfile(profile)
        val oldToken = repository.requestRun(profile.id)!!
        runtime.states = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Stopped, null, false))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.onStateWritten = {
            entered.complete(Unit)
            release.await()
        }
        runtime.onStart = {
            runtime.states = listOf(FrpRuntimeState(profile.id, profile.type, FrpInstanceStatus.Running, null, true))
        }
        val start = async { repository.runProfile(profile.id) }
        entered.await()
        val observation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { repository.syncRuntimeStates() }
        assertFalse(start.isCompleted)
        assertFalse(observation.isCompleted)
        release.complete(Unit)
        assertTrue(start.await()!!.isSuccess)
        observation.await()
        val state = dao.getRuntimeStates().single()
        assertEquals(FrpInstanceStatus.Running, state.state)
        assertTrue(state.desiredRunning)
        assertNotEquals(oldToken, state.runToken)
        assertNull(repository.runProfile(profile.id, oldToken))
        assertTrue(repository.runProfile(profile.id, restart = true)!!.isSuccess)
        assertEquals(1, runtime.reloaded.size)
        repository.stop(profile)
        assertNull(repository.runProfile(profile.id, state.runToken))
    }

    @Test fun rebootInvalidatesOldWorkAndRespectsBothBootSwitches() = runTest {
        for (bootEnabled in listOf(false, true)) {
            val dao = MemoryDao()
            val runtime = FakeRuntime().apply { bootCount = 2 }
            val settings = MemorySettings(FrpSettings(
                runtimeBootCount = 1, bootStartEnabled = bootEnabled, autoRetryEnabled = true,
            ))
            val repository = FrpRepository(dao, settings, runtime, cipher, backgroundScope)
            val automatic = profile.copy(id = "automatic", autoStart = true)
            for (item in listOf(profile, automatic)) {
                repository.createProfile(item)
                dao.upsertRuntimeState(FrpRuntimeStateEntity(item.id, item.type,
                    FrpInstanceStatus.Running, null, true, "previous-boot"))
            }
            repository.ensureRuntimeReady()
            assertEquals(2, repository.settings.first().runtimeBootCount)
            assertFalse(repository.canRecover(profile.id, "previous-boot", RecoveryReason.AutoRetry))
            assertFalse(repository.canRecover(automatic.id, "previous-boot", RecoveryReason.AutoRetry))
            repository.restoreDesiredProfiles()
            assertEquals(if (bootEnabled) listOf(automatic.id) else emptyList<String>(), runtime.started.map { it.id })
        }
    }

    @Test fun batchChangesPreserveConcurrentEditsAndSynchronizeOnce() = runTest {
        for (rootMode in listOf(false, true)) {
            val dao = MemoryDao()
            val runtime = FakeRuntime()
            val repository = FrpRepository(dao, MemorySettings(FrpSettings(rootMode = rootMode)), runtime, cipher, backgroundScope)
            val changed = repository.createProfile(profile.copy(id = "changed", autoStart = false))
            val enabled = repository.createProfile(profile.copy(id = "enabled", autoStart = true))
            val stale = repository.createProfile(profile.copy(id = "stale", autoStart = false))
            val edited = repository.upsertProfile(stale.copy(toml = "serverAddr = 'new'"), stale.updatedAt)
            runtime.configureCount = 0
            val result = repository.applyProfileBatch(listOf(changed, enabled, stale, profile.copy(id = "missing")), ProfileBatchAction.EnableAutoStart)
            assertEquals(setOf(changed.id), result.succeeded)
            assertEquals(setOf(enabled.id, "missing"), result.skipped)
            assertEquals(mapOf(stale.id to "CONFIG_CHANGED"), result.failures)
            assertTrue(result.pending.isEmpty())
            assertNull(result.syncError)
            assertTrue(repository.getProfile(changed.id)!!.autoStart)
            assertEquals(edited, repository.getProfile(stale.id))
            assertEquals(1, runtime.configureCount)
            assertFalse(repository.settings.first().runtimeSyncPending)

            runtime.configureResult = FrpResult("OFFLINE", "Runtime unavailable")
            val disabled = repository.applyProfileBatch(listOf(repository.getProfile(changed.id)!!), ProfileBatchAction.DisableAutoStart)
            assertEquals(setOf(changed.id), disabled.succeeded)
            assertTrue(disabled.syncError!!.contains("CONFIG_SAVED_SYNC_FAILED"))
            assertFalse(repository.getProfile(changed.id)!!.autoStart)
            assertEquals(rootMode, repository.settings.first().runtimeSyncPending)
        }
    }

    @Test fun batchDeletionPreservesFailedAndCancelledProfiles() = runTest {
        for (rootMode in listOf(false, true)) {
            val dao = MemoryDao()
            val runtime = FakeRuntime()
            val repository = FrpRepository(dao, MemorySettings(FrpSettings(rootMode = rootMode)), runtime, cipher, backgroundScope)
            val failed = repository.createProfile(profile.copy(id = "failed"))
            val deleted = repository.createProfile(profile.copy(id = "deleted", files = mapOf("key.pem" to "key".toByteArray())))
            val pending = repository.createProfile(profile.copy(id = "pending"))
            repository.ensureRuntimeReady()
            repository.setTileProfileId(deleted.id)
            dao.upsertRuntimeState(FrpRuntimeStateEntity(failed.id, failed.type, FrpInstanceStatus.Running, null, true, "old-token"))
            runtime.stopResult = FrpResult("STOP_FAILED", "Stop failed")
            runtime.configureCount = 0
            var processed = 0
            val result = repository.applyProfileBatch(listOf(failed, deleted, pending), ProfileBatchAction.Delete,
                onProgress = { processed = it }, shouldCancel = { processed == 2 })
            assertEquals(setOf(deleted.id), result.succeeded)
            assertEquals(mapOf(failed.id to "Stop failed"), result.failures)
            assertEquals(setOf(pending.id), result.pending)
            assertEquals(failed, repository.getProfile(failed.id))
            assertEquals(pending, repository.getProfile(pending.id))
            assertNull(repository.getProfile(deleted.id))
            assertTrue(dao.getCertificates(deleted.id).isEmpty())
            assertEquals("", repository.settings.first().tileProfileId)
            assertFalse(repository.canRecover(failed.id, "old-token", RecoveryReason.AutoRetry))
            assertEquals(1, runtime.configureCount)
        }
    }

    @Test fun moduleActionsPersistModeOnlyAfterSuccess() = runTest {
        for (action in listOf("install", "start", "stop", "uninstall")) {
            for (failure in listOf(false, true)) {
                val enabling = action == "install" || action == "start"
                val dao = MemoryDao()
                val runtime = FakeRuntime().apply {
                    moduleResult = ModuleStatus(error = if (failure) "ROOT_UNAVAILABLE" else null)
                }
                val settings = MemorySettings(FrpSettings(rootMode = !enabling))
                val repository = FrpRepository(dao, settings, runtime, cipher, backgroundScope)
                repository.createProfile(profile)
                val result = repository.manageModule(action)
                assertEquals(failure, result.error != null)
                val expectedMode = if (failure) !enabling else enabling
                assertEquals(expectedMode, runtime.rootMode)
                assertEquals(expectedMode, repository.settings.first().rootMode)
                if (!failure && enabling) {
                    assertEquals(profile.id, runtime.configured.single().id)
                    assertTrue(runtime.configured.single().networkReconnect)
                    assertFalse(repository.settings.first().runtimeSyncPending)
                }
            }
        }
    }
}

private class FakeRuntime : FrpRuntimeGateway {
    override var rootMode = false
    override val localRuntimeRunning = false
    override var bootCount = 1
    var states = emptyList<FrpRuntimeState>()
    val started = mutableListOf<FrpProfile>()
    val reloaded = mutableListOf<FrpProfile>()
    var reloadResult = FrpResult(null, "")
    var stopResult = FrpResult(null, "")
    var configureResult = FrpResult(null, "")
    var validationResult = FrpResult(null, "")
    var pruneResult = FrpResult(null, "")
    var moduleResult = ModuleStatus()
    var capturedLogSink: FrpLogSink? = null
    var configured = emptyList<FrpProfile>()
    var configureCount = 0
    var onStart: suspend () -> Unit = {}
    override fun validateToml(toml: String) = validationResult
    override fun tlsFilePaths(toml: String) = emptyList<String>()
    override fun setLogSink(sink: FrpLogSink) { capturedLogSink = sink }
    override suspend fun start(profile: FrpProfile): FrpResult { started += profile; onStart(); return FrpResult(null, "") }
    override suspend fun reload(profile: FrpProfile): FrpResult { reloaded += profile; return reloadResult }
    override suspend fun stop(id: String) = stopResult
    override suspend fun delete(id: String) = FrpResult(null, "")
    override suspend fun stopAll() = FrpResult(null, "")
    override suspend fun configure(profiles: List<FrpProfile>): FrpResult { configureCount++; configured = profiles; return configureResult }
    override suspend fun module(action: String) = if (action == "status") ModuleStatus() else moduleResult
    override suspend fun listInstances() = FrpRuntimeQueryResult.Success(states)
    override suspend fun awaitRuntimeChange() = Unit
    override suspend fun pruneLogCache(before: Long) = pruneResult
}

private class MemorySettings(initial: FrpSettings = FrpSettings()) : SettingsGateway {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<FrpSettings> = state
    override suspend fun setRuntimeBootCount(count: Int) { state.value = state.value.copy(runtimeBootCount = count) }
    override suspend fun setBootStartEnabled(enabled: Boolean) { state.value = state.value.copy(bootStartEnabled = enabled) }
    override suspend fun setNetworkReconnectEnabled(enabled: Boolean) { state.value = state.value.copy(networkReconnectEnabled = enabled) }
    override suspend fun setAutoRetryEnabled(enabled: Boolean) { state.value = state.value.copy(autoRetryEnabled = enabled) }
    override suspend fun setScreenOffKeepAliveEnabled(enabled: Boolean) { state.value = state.value.copy(screenOffKeepAliveEnabled = enabled) }
    override suspend fun setLogRetentionDays(days: Int) { state.value = state.value.copy(logRetentionDays = days) }
    override suspend fun setThemeSeedColor(color: Int) { state.value = state.value.copy(themeSeedColor = color) }
    override suspend fun setLanguageMode(mode: LanguageMode) { state.value = state.value.copy(languageMode = mode) }
    override suspend fun setThemeMode(mode: ThemeMode) { state.value = state.value.copy(themeMode = mode) }
    override suspend fun setRootMode(enabled: Boolean) { state.value = state.value.copy(rootMode = enabled) }
    override suspend fun setLaunchStartEnabled(enabled: Boolean) { state.value = state.value.copy(launchStartEnabled = enabled) }
    override suspend fun setExcludeFromRecents(enabled: Boolean) { state.value = state.value.copy(excludeFromRecents = enabled) }
    override suspend fun setLiveUpdatesEnabled(enabled: Boolean) { state.value = state.value.copy(liveUpdatesEnabled = enabled) }
    override suspend fun setExternalReadEnabled(enabled: Boolean) { state.value = state.value.copy(externalReadEnabled = enabled) }
    override suspend fun setExternalWriteEnabled(enabled: Boolean) { state.value = state.value.copy(externalWriteEnabled = enabled) }
    override suspend fun setAutomationToken(encrypted: String) { state.value = state.value.copy(automationToken = encrypted) }
    override suspend fun setTileProfileId(id: String) { state.value = state.value.copy(tileProfileId = id) }
    override suspend fun setRuntimeSyncPending(pending: Boolean) { state.value = state.value.copy(runtimeSyncPending = pending) }
    override suspend fun setPendingStart(pending: Boolean) { state.value = state.value.copy(pendingStart = pending) }
}

private class MemoryDao : FrpDao {
    val recordedLogs = mutableListOf<FrpLogEntity>()
    var clearFailure = false
    var onStateWritten: suspend () -> Unit = {}
    private val profiles = linkedMapOf<String, FrpProfileEntity>()
    private val states = linkedMapOf<String, FrpRuntimeStateEntity>()
    private val certificates = linkedMapOf<Pair<String, String>, CertificateEntity>()
    private var settings = FrpSettings()
    override fun observeSettings() = flowOf(settings)
    override suspend fun getSettings() = settings
    override suspend fun upsertSettings(settings: FrpSettings) { this.settings = settings }
    override fun observeProfiles() = flow { emit(profiles.values.toList()) }
    override suspend fun getProfiles() = profiles.values.toList()
    override suspend fun getProfile(id: String) = profiles[id]
    override suspend fun getAutoStartProfiles() = profiles.values.filter { it.autoStart }
    override suspend fun upsertProfile(profile: FrpProfileEntity) { profiles[profile.id] = profile }
    override suspend fun insertProfile(profile: FrpProfileEntity) {
        check(profile.id !in profiles)
        profiles[profile.id] = profile
    }
    override suspend fun deleteProfile(id: String) { profiles.remove(id) }
    override suspend fun getCertificates(id: String) = certificates.values.filter { it.profileId == id }
    override suspend fun upsertCertificate(certificate: CertificateEntity) { certificates[certificate.profileId to certificate.name] = certificate }
    override suspend fun deleteCertificates(id: String) { certificates.keys.removeAll { it.first == id } }
    override fun observeRuntimeStates() = flowOf(states.values.toList())
    override suspend fun getRuntimeStates() = states.values.toList()
    override suspend fun getDesiredRunningStates() = states.values.filter { it.desiredRunning }
    override suspend fun upsertRuntimeState(state: FrpRuntimeStateEntity) { states[state.id] = state; onStateWritten() }
    override suspend fun deleteRuntimeState(id: String) { states.remove(id) }
    override fun observeLogs(instanceId: String?, type: String?, level: String?, keyword: String?, limit: Int, maxUid: Long?) = flowOf(emptyList<FrpLogEntity>())
    override suspend fun latestLogId(): Long = recordedLogs.maxOfOrNull { it.uid } ?: 0
    override suspend fun insertLogs(logs: List<FrpLogEntity>) { recordedLogs += logs }
    override suspend fun trimLogs(maxCount: Int) = Unit
    override suspend fun deleteLogsOlderThan(olderThan: Long) = Unit
    override suspend fun clearLogs() {
        check(!clearFailure) { "Storage unavailable" }
        recordedLogs.clear()
    }
    override fun observeOldestLogTime(): Flow<Long?> = flowOf(null)
}
