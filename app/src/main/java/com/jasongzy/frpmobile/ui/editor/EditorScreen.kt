package com.jasongzy.frpmobile.ui.editor

import com.jasongzy.frpmobile.R
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.data.EditorDraft
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.TlsFileInfo
import com.jasongzy.frpmobile.core.data.TlsFileRole
import com.jasongzy.frpmobile.core.data.readLimited
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.newProfileDraft
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import com.jasongzy.frpmobile.core.runtime.FrpRuntimePermissions
import com.jasongzy.frpmobile.ui.components.EmptyState
import com.jasongzy.frpmobile.ui.components.ChoiceDialog
import com.jasongzy.frpmobile.ui.components.ErrorText
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.jasongzy.frpmobile.core.data.FrpSettings
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class EditorOperation { Validate, Save, Restart, Certificates, Discard }

data class EditorUiState(
    val profile: FrpProfile? = null,
    val isNew: Boolean = false,
    val loading: Boolean = true,
    val name: String = "",
    val toml: TextFieldState = TextFieldState(),
    val validationMessage: String? = null,
    val operation: EditorOperation? = null,
    val files: Map<String, ByteArray> = emptyMap(),
    val tlsMessage: String? = null,
    val tlsPathToCopy: String? = null,
    val exitRequested: Boolean = false,
    val toastMessageRes: Int? = null,
) {
    val isBusy: Boolean get() = operation != null
    val hasUnsavedChanges: Boolean
        get() = profile?.let {
            isNew || it.name != name || !it.toml.contentEquals(toml.text) ||
                it.files.keys != files.keys || files.any { (name, bytes) -> !bytes.contentEquals(it.files[name]) }
        } == true
    val tlsFiles: List<TlsFileInfo>
        get() = TlsFileRole.entries.filter { it.fileName in files }.map { it.fileInfo }

    fun reconcileSaved(saved: FrpProfile?): EditorUiState {
        if (saved == null || saved.id != profile?.id || saved.storageError) return this
        val reconciled = copy(profile = saved, isNew = false)
        return if (reconciled.hasUnsavedChanges) this else reconciled
    }
}

@OptIn(FlowPreview::class)
class EditorViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val mutableState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = mutableState
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FrpSettings())

    private val drafts = AppGraph.editorDrafts(application)
    private val sessionId = savedStateHandle.get<String>("draftSession")
        ?: UUID.randomUUID().toString().also { savedStateHandle["draftSession"] = it }
    private val draftMutex = Mutex()
    private var closed = false
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            uiState.map { state ->
                if (state.profile != null && !state.loading && !state.profile.storageError) state.toDraft() to state.toml else null
            }.distinctUntilChanged().collectLatest { content ->
                if (content != null) {
                    val (draft, text) = content
                    snapshotFlow {
                        draft.copy(current = draft.current.copy(toml = text.text.toString()),
                            selectionStart = text.selection.start, selectionEnd = text.selection.end)
                    }.debounce(300).collect { writeDraft(it) }
                }
            }
        }
    }

    private fun EditorUiState.toDraft() = EditorDraft(
        requireNotNull(profile), profile.copy(name = name, toml = toml.text.toString(), files = files),
        isNew, toml.selection.start, toml.selection.end,
    )

    private suspend fun writeDraft(draft: EditorDraft) = draftMutex.withLock {
        if (closed) return@withLock
        try { withContext(Dispatchers.IO) { drafts.write(sessionId, draft) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            reportResult(R.string.editor_draft_save_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    fun persistDraft() {
        val state = mutableState.value
        if (state.profile == null || state.loading || state.profile.storageError || closed) return
        val draft = state.toDraft()
        viewModelScope.launch { writeDraft(draft) }
    }

    private suspend fun closeDraft() = draftMutex.withLock {
        withContext(Dispatchers.IO) { drafts.delete(sessionId) }
        closed = true
    }

    fun discard() {
        if (mutableState.value.isBusy) return
        mutableState.update { it.copy(operation = EditorOperation.Discard) }
        viewModelScope.launch {
            try {
                closeDraft()
                mutableState.update { it.copy(exitRequested = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportResult(R.string.editor_draft_save_failed, e.message ?: e.javaClass.simpleName) }
            finally { mutableState.update { it.copy(operation = null) } }
        }
    }

    fun newDraft(type: FrpType) = open(null, type)
    fun load(profileId: String) = open(profileId, null)

    private fun open(profileId: String?, type: FrpType?) {
        if (loadJob != null) return
        loadJob = viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    drafts.prune(sessionId)
                    drafts.read(sessionId)
                }
                val saved = (restored?.original?.id ?: profileId ?: savedStateHandle.get<String>("profileId"))?.let {
                    repository.getProfile(it, includeFiles = true)
                }
                val profile = restored?.original ?: saved ?: type?.let(::newProfileDraft)
                val current = restored?.current ?: profile
                val text = current?.toml.orEmpty()
                mutableState.value = EditorUiState(
                    profile = profile,
                    isNew = restored?.isNew ?: (profileId == null && savedStateHandle.get<String>("profileId") == null),
                    loading = false,
                    name = current?.name.orEmpty(),
                    toml = TextFieldState(text, TextRange(
                        (restored?.selectionStart ?: 0).coerceIn(0, text.length),
                        (restored?.selectionEnd ?: 0).coerceIn(0, text.length),
                    )),
                    files = current?.files.orEmpty(),
                    validationMessage = if (profile?.storageError == true) getApplication<Application>().getString(R.string.editor_storage_error) else null,
                ).reconcileSaved(saved)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { it.copy(loading = false) }
                reportResult(R.string.editor_draft_load_failed, getApplication<Application>().getString(R.string.editor_draft_load_failed) + "\n" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun setName(name: String) {
        mutableState.update { if (it.isBusy) it else it.copy(name = name) }
    }

    fun validate() {
        val state = mutableState.value
        if (state.isBusy || state.profile == null || state.profile.storageError) return
        val draft = state.profile.copy(toml = state.toml.text.toString(), files = state.files)
        mutableState.update { it.copy(operation = EditorOperation.Validate, validationMessage = null) }
        viewModelScope.launch {
            try {
                val result = repository.verify(draft)
                reportResult(
                    if (result.isSuccess) R.string.editor_valid else R.string.editor_invalid,
                    if (result.isSuccess) null else result.message,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportResult(R.string.editor_invalid, e.message ?: e.javaClass.simpleName)
            } finally {
                mutableState.update { it.copy(operation = null) }
            }
        }
    }

    private fun reportResult(messageRes: Int, error: String? = null, exitRequested: Boolean = false) {
        mutableState.update {
            it.copy(
                validationMessage = error,
                toastMessageRes = messageRes,
                exitRequested = exitRequested,
            )
        }
    }

    fun consumeToast() {
        mutableState.update { it.copy(toastMessageRes = null) }
    }

    fun save() {
        val state = mutableState.value
        val profile = state.profile ?: return
        if (state.isBusy) return
        mutableState.update { it.copy(operation = EditorOperation.Save, validationMessage = null) }
        viewModelScope.launch {
            try {
                val updated = profile.copy(name = state.name, toml = state.toml.text.toString(), files = state.files)
                val stored = if (state.isNew) {
                    repository.createProfile(updated)
                } else repository.upsertProfile(updated, profile.updatedAt, state.files)
                savedStateHandle["profileId"] = updated.id
                mutableState.update {
                    it.copy(
                        profile = stored.copy(files = state.files),
                        isNew = false,
                        toastMessageRes = R.string.editor_saved,
                    )
                }
                closeDraft()
                mutableState.update { it.copy(exitRequested = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.startsWith("CONFIG_SAVED_SYNC_FAILED") == true) reconcileSavedProfile(profile.id)
                reportResult(R.string.editor_save_failed, saveErrorMessage(e))
            } finally {
                mutableState.update { it.copy(operation = null) }
            }
        }
    }

    fun onExitHandled() {
        mutableState.update { it.copy(exitRequested = false) }
    }

    fun saveAndRestart(context: Context) {
        val state = mutableState.value
        val profile = state.profile ?: return
        if (state.isBusy) return
        mutableState.update { it.copy(operation = EditorOperation.Restart, validationMessage = null) }
        viewModelScope.launch {
            try {
                val updated = profile.copy(name = state.name, toml = state.toml.text.toString(), files = state.files)
                val (stored, reloaded) = if (state.isNew) {
                    val validation = withContext(Dispatchers.IO) { repository.validateToml(updated.toml) }
                    require(validation.isSuccess) { validation.message }
                    repository.createProfile(updated) to false
                } else repository.saveAndRestart(updated, profile.updatedAt, state.files)
                savedStateHandle["profileId"] = stored.id
                mutableState.update { it.copy(profile = stored.copy(files = state.files), isNew = false) }
                val launchError = if (reloaded) null else {
                    FrpForegroundService.startProfile(context, stored.id).await().takeUnless { it.isSuccess }?.message
                }
                if (launchError == null) closeDraft()
                reportResult(
                    if (launchError == null) R.string.editor_saved_restarted else R.string.editor_saved_start_failed,
                    launchError,
                    exitRequested = launchError == null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.startsWith("CONFIG_SAVED_SYNC_FAILED") == true) reconcileSavedProfile(profile.id)
                reportResult(R.string.editor_save_restart_failed, saveErrorMessage(e))
            } finally {
                mutableState.update { it.copy(operation = null) }
            }
        }
    }

    private suspend fun reconcileSavedProfile(id: String) {
        val stored = try { repository.getProfile(id, includeFiles = true) ?: return }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return }
        mutableState.update { it.reconcileSaved(stored) }
        if (mutableState.value.profile == stored) savedStateHandle["profileId"] = id
    }

    private fun saveErrorMessage(error: Exception): String {
        val messageRes = when (error.message) {
            "CONFIG_CHANGED" -> R.string.editor_config_changed
            "PROFILE_NAME_REQUIRED" -> R.string.editor_name_required
            "PROFILE_NAME_TOO_LONG" -> R.string.editor_name_too_long
            "CONFIG_TOO_LARGE" -> R.string.editor_config_too_large
            else -> return error.message ?: error.javaClass.simpleName
        }
        return getApplication<Application>().getString(messageRes)
    }

    private fun changeTls(block: suspend () -> Pair<String, String?>) {
        if (mutableState.value.profile == null || mutableState.value.isBusy) return
        mutableState.update { it.copy(operation = EditorOperation.Certificates) }
        viewModelScope.launch {
            try {
                val (message, path) = block()
                mutableState.update { it.copy(tlsMessage = message, tlsPathToCopy = path) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(tlsMessage = e.message ?: e.javaClass.simpleName) } }
            finally { mutableState.update { it.copy(operation = null) } }
        }
    }

    fun importTlsFile(uri: Uri, role: TlsFileRole, successMessage: String, openFailedMessage: String) = changeTls {
        val content = withContext(Dispatchers.IO) {
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error(openFailedMessage)
            input.use { it.readLimited(1024 * 1024) }.also { require(it.isNotEmpty()) { "Empty certificate" } }
        }
        mutableState.update { it.copy(files = it.files + (role.fileName to content)) }
        successMessage to role.fileInfo.path
    }

    fun deleteTlsFile(role: TlsFileRole, successMessage: String) = changeTls {
        mutableState.update { it.copy(files = it.files - role.fileName) }
        successMessage to null
    }

    fun consumeTlsMessage() {
        mutableState.update { it.copy(tlsMessage = null, tlsPathToCopy = null) }
    }
}

@Composable
fun EditorScreen(
    profileId: String? = null,
    newType: FrpType? = null,
    onExit: () -> Unit,
    viewModel: EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.persistDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var saveDialog by rememberSaveable(profileId) { mutableStateOf(false) }
    BackHandler {
        if (!state.isBusy) {
            if (state.hasUnsavedChanges) saveDialog = true else viewModel.discard()
        }
    }
    LaunchedEffect(state.toastMessageRes, state.exitRequested) {
        state.toastMessageRes?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
        if (state.exitRequested) {
            saveDialog = false
            viewModel.onExitHandled()
            onExit()
        }
    }
    if (saveDialog) AlertDialog(
        onDismissRequest = { if (!state.isBusy) saveDialog = false },
        title = { Text(stringResource(R.string.editor_save_changes_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.editor_save_changes_message))
                state.validationMessage?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = !state.isBusy) {
                EditorActionLabel(stringResource(R.string.editor_save), state.operation == EditorOperation.Save)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { saveDialog = false }, enabled = !state.isBusy) {
                    Text(stringResource(R.string.editor_keep_editing))
                }
                TextButton(
                    onClick = viewModel::discard,
                    enabled = !state.isBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.editor_discard))
                }
            }
        },
    )
    val tlsImportedText = stringResource(R.string.editor_tls_imported)
    val tlsOpenFailedText = stringResource(R.string.editor_tls_open_failed)
    val tlsDeletedText = stringResource(R.string.editor_tls_deleted)
    val tlsPathCopiedText = stringResource(R.string.editor_tls_path_copied)
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var pendingSaveRestart by rememberSaveable { mutableStateOf(false) }
    var tlsDialog by rememberSaveable { mutableStateOf(false) }
    var pendingTlsUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var deleteTlsRole by rememberSaveable { mutableStateOf<TlsFileRole?>(null) }
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if ((settings.rootMode || FrpRuntimePermissions.canStart(context)) && pendingSaveRestart) {
            viewModel.saveAndRestart(context)
        } else if (pendingSaveRestart) {
            Toast.makeText(context, R.string.editor_start_permission_required, Toast.LENGTH_SHORT).show()
        }
        pendingSaveRestart = false
    }
    val tlsFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingTlsUri = uri
    }

    fun saveAndRestartWithPermission() {
        val missing = FrpRuntimePermissions.missingPermissions(context, settings.rootMode)
        if (missing.isEmpty()) {
            viewModel.saveAndRestart(context)
        } else {
            pendingSaveRestart = true
            runtimePermissionsLauncher.launch(missing)
        }
    }

    LaunchedEffect(profileId, newType) {
        if (newType != null) viewModel.newDraft(newType) else viewModel.load(requireNotNull(profileId))
    }
    LaunchedEffect(state.tlsMessage) {
        state.tlsMessage?.let { message ->
            state.tlsPathToCopy?.let { path -> copyText(context, path) }
            snackbarHostState.showSnackbar(message)
            viewModel.consumeTlsMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        if (state.loading) {
            CircularWavyProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        if (state.profile == null) {
            EmptyState(text = state.validationMessage ?: stringResource(R.string.editor_profile_missing))
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FrpUiTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                enabled = state.profile?.storageError != true,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TomlEditor(
                state = state.toml,
                type = state.profile?.type ?: FrpType.Client,
                enabled = state.profile?.storageError != true,
                busy = state.isBusy,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            state.validationMessage?.let { message ->
                SelectionContainer(Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState())) {
                    ErrorText(message)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = viewModel::validate, enabled = !state.isBusy && state.profile?.storageError != true) {
                    EditorActionLabel(stringResource(R.string.editor_validate), state.operation == EditorOperation.Validate)
                }
                FilledTonalButton(onClick = viewModel::save, enabled = !state.isBusy && state.profile?.storageError != true) {
                    EditorActionLabel(stringResource(R.string.editor_save), state.operation == EditorOperation.Save)
                }
                Button(onClick = { saveAndRestartWithPermission() }, enabled = !state.isBusy && state.profile?.storageError != true) {
                    EditorActionLabel(stringResource(R.string.editor_save_restart), state.operation == EditorOperation.Restart)
                }
                FrpIconButton(
                    label = stringResource(R.string.editor_tls_files),
                    onClick = { tlsDialog = true },
                    enabled = !state.isBusy && state.profile?.storageError != true,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (tlsDialog) {
        TlsFilesDialog(
            files = state.tlsFiles,
            onDismiss = { tlsDialog = false },
            onImport = { tlsFileLauncher.launch(arrayOf("*/*")) },
            onCopyPath = { file ->
                copyText(context, file.path)
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(tlsPathCopiedText)
                }
            },
            onDelete = { file ->
                if (file.role == TlsFileRole.PrivateKey) {
                    deleteTlsRole = file.role
                } else {
                    viewModel.deleteTlsFile(file.role, tlsDeletedText)
                }
            },
        )
    }

    pendingTlsUri?.let { uri ->
        TlsRoleDialog(
            onDismiss = { pendingTlsUri = null },
            onSelect = { role ->
                pendingTlsUri = null
                viewModel.importTlsFile(uri, role, tlsImportedText, tlsOpenFailedText)
            },
        )
    }

    deleteTlsRole?.let { role ->
        AlertDialog(
            onDismissRequest = { deleteTlsRole = null },
            title = { Text(stringResource(R.string.editor_tls_delete_private_key)) },
            text = { Text(stringResource(R.string.editor_tls_delete_private_key_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTlsRole = null
                        viewModel.deleteTlsFile(role, tlsDeletedText)
                    },
                ) {
                    Text(stringResource(R.string.editor_tls_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTlsRole = null }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EditorActionLabel(text: String, busy: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Text(text, modifier = Modifier.alpha(if (busy) 0f else 1f))
        if (busy) CircularWavyProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun TlsFilesDialog(
    files: List<TlsFileInfo>,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onCopyPath: (TlsFileInfo) -> Unit,
    onDelete: (TlsFileInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.editor_tls_files), modifier = Modifier.weight(1f))
                FrpIconButton(label = stringResource(R.string.editor_tls_import), onClick = onImport) {
                    Icon(
                        imageVector = Icons.Rounded.FileOpen,
                        contentDescription = null,
                    )
                }
            }
        },
        text = {
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_tls_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    files.forEachIndexed { index, file ->
                        TlsFileRow(file, onCopyPath, onDelete)
                        if (index < files.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_tls_close))
            }
        },
    )
}

@Composable
private fun TlsFileRow(
    file: TlsFileInfo,
    onCopyPath: (TlsFileInfo) -> Unit,
    onDelete: (TlsFileInfo) -> Unit,
) {
    val icon = when (file.role) {
        TlsFileRole.TrustedCa -> Icons.Rounded.VerifiedUser
        TlsFileRole.Certificate -> Icons.Rounded.Badge
        TlsFileRole.PrivateKey -> Icons.Rounded.Key
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(tlsRoleLabel(file.role), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FrpIconButton(label = stringResource(R.string.editor_tls_copy_path), onClick = { onCopyPath(file) }) {
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = null,
            )
        }
        FrpIconButton(label = stringResource(R.string.editor_tls_delete), onClick = { onDelete(file) }) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TlsRoleDialog(
    onDismiss: () -> Unit,
    onSelect: (TlsFileRole) -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.editor_tls_choose_role),
        onDismiss = onDismiss,
        entries = TlsFileRole.entries.map { role ->
            tlsRoleLabel(role) to { onSelect(role) }
        },
    )
}

@Composable
private fun tlsRoleLabel(role: TlsFileRole): String =
    stringResource(
        when (role) {
            TlsFileRole.TrustedCa -> R.string.editor_tls_trusted_ca
            TlsFileRole.Certificate -> R.string.editor_tls_certificate
            TlsFileRole.PrivateKey -> R.string.editor_tls_private_key
        },
    )

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("TLS file path", text))
}
