package com.jasongzy.frpmobile.ui.profiles

import com.jasongzy.frpmobile.R
import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.withResumed
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.ProfileBatchAction
import com.jasongzy.frpmobile.core.data.readLimited
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.TomlValidator
import com.jasongzy.frpmobile.core.frp.newProfileDraft
import com.jasongzy.frpmobile.ui.components.EmptyState
import com.jasongzy.frpmobile.ui.components.ChoiceDialog
import com.jasongzy.frpmobile.ui.components.ErrorText
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileBatchConfirmation(val action: ProfileBatchAction, val profiles: List<FrpProfile>)
data class ProfileBatchProgress(val completed: Int, val total: Int, val cancelling: Boolean = false)

data class ProfileImportDraft(val toml: String = "", val type: FrpType? = null)

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val appContext = application.applicationContext
    val profiles: StateFlow<List<FrpProfile>?> = repository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val mutableBusyProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val busyProfileIds: StateFlow<Set<String>> = mutableBusyProfileIds
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    private val mutableImportDraft = MutableStateFlow<ProfileImportDraft?>(null)
    val importDraft: StateFlow<ProfileImportDraft?> = mutableImportDraft
    val importing = MutableStateFlow(false)
    private val mutableImportedProfileId = MutableStateFlow<String?>(null)
    val importedProfileId: StateFlow<String?> = mutableImportedProfileId
    private val mutableSelectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = mutableSelectedIds
    val batchConfirmation = MutableStateFlow<ProfileBatchConfirmation?>(null)
    val batchProgress = MutableStateFlow<ProfileBatchProgress?>(null)
    val batchToast = MutableStateFlow<String?>(null)

    fun consumeBatchToast() { batchToast.value = null }

    fun onEditorOpened() { mutableImportedProfileId.value = null }

    private fun execute(block: suspend () -> Unit) = viewModelScope.launch {
        mutableError.value = null
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableError.value = e.message ?: e.javaClass.simpleName }
    }

    private fun executeProfile(id: String, block: suspend () -> Unit) {
        if (batchProgress.value != null || id in mutableBusyProfileIds.value) return
        mutableBusyProfileIds.update { it + id }
        execute {
            try { block() }
            finally { mutableBusyProfileIds.update { it - id } }
        }
    }

    fun toggleAutoStart(profile: FrpProfile, enabled: Boolean) = executeProfile(profile.id) {
        repository.upsertProfile(profile.copy(autoStart = enabled), profile.updatedAt)
    }

    fun delete(profile: FrpProfile) = executeProfile(profile.id) {
        val result = repository.deleteProfile(profile.id)
        if (!result.isSuccess) mutableError.value = result.message
    }

    fun toggleSelection(id: String) {
        if (batchProgress.value == null) mutableSelectedIds.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        if (batchProgress.value == null) mutableSelectedIds.value = profiles.value.orEmpty().map { it.id }.toSet()
    }

    fun clearSelection() {
        if (batchProgress.value == null) mutableSelectedIds.value = emptySet()
    }

    fun pruneSelection() {
        mutableSelectedIds.update { it.intersect(profiles.value.orEmpty().map { profile -> profile.id }.toSet()) }
    }

    fun prepareBatch(action: ProfileBatchAction) {
        if (batchProgress.value != null || mutableBusyProfileIds.value.isNotEmpty()) return
        val targets = profiles.value.orEmpty().filter { it.id in mutableSelectedIds.value }
        if (targets.isNotEmpty()) batchConfirmation.value = ProfileBatchConfirmation(action, targets)
    }

    fun cancelBatch() { batchProgress.update { it?.copy(cancelling = true) } }

    fun confirmBatch() {
        val confirmation = batchConfirmation.value ?: return
        if (batchProgress.value != null || mutableBusyProfileIds.value.isNotEmpty()) return
        batchConfirmation.value = null
        batchProgress.value = ProfileBatchProgress(0, confirmation.profiles.size)
        execute {
            try {
                val result = repository.applyProfileBatch(
                    confirmation.profiles, confirmation.action,
                    onProgress = { completed -> batchProgress.update { it?.copy(completed = completed) } },
                    shouldCancel = { batchProgress.value?.cancelling == true },
                )
                mutableSelectedIds.value = result.failures.keys + result.pending
                batchToast.value = listOfNotNull(
                    result.succeeded.size.takeIf { it > 0 }?.let { appContext.getString(R.string.profiles_batch_succeeded, it) },
                    result.skipped.size.takeIf { it > 0 }?.let { appContext.getString(R.string.profiles_batch_skipped, it) },
                    result.failures.size.takeIf { it > 0 }?.let { appContext.getString(R.string.profiles_batch_failed, it) },
                    result.pending.size.takeIf { it > 0 }?.let { appContext.getString(R.string.profiles_batch_pending, it) },
                    result.syncError?.let { appContext.getString(R.string.profiles_batch_sync_failed) },
                ).joinToString(" · ")
                val failures = result.failures.map { (id, message) ->
                    "${confirmation.profiles.first { it.id == id }.name}: $message"
                }
                mutableError.value = (failures + listOfNotNull(result.syncError)).joinToString("\n\n").ifBlank { null }
            } finally { batchProgress.value = null }
        }
    }

    fun openPasteImport() {
        mutableError.value = null
        mutableImportDraft.value = ProfileImportDraft()
    }

    fun openFileImport(uri: Uri) {
        execute {
            mutableImportDraft.value = ProfileImportDraft(withContext(Dispatchers.IO) { readToml(appContext, uri) })
        }
    }

    fun updateImportDraft(draft: ProfileImportDraft) {
        if (!importing.value) mutableImportDraft.value = draft
    }

    fun dismissImport() {
        if (!importing.value) mutableImportDraft.value = null
    }

    fun confirmImport(type: FrpType, toml: String) {
        if (importing.value) return
        importing.value = true
        execute {
            try {
                val profile = newProfileDraft(type, toml)
                repository.createProfile(profile)
                mutableImportDraft.value = null
                mutableImportedProfileId.value = profile.id
            } finally { importing.value = false }
        }
    }
}

@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    focusProfileId: String?,
    onProfileFocusHandled: (String) -> Unit,
    onEditProfile: (String) -> Unit,
    onNewProfile: (FrpType) -> Unit,
) {
    val profileSnapshot by viewModel.profiles.collectAsStateWithLifecycle()
    val profiles = profileSnapshot.orEmpty()
    val currentProfiles by rememberUpdatedState(profiles)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()
    var highlightedProfileId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusProfileId, profileSnapshot != null) {
        if (focusProfileId == null || profileSnapshot == null) return@LaunchedEffect
        try {
            lifecycle.withResumed { }
            val index = currentProfiles.indexOfFirst { it.id == focusProfileId }
            if (index < 0) return@LaunchedEffect
            listState.animateScrollToItem(index)
            highlightedProfileId = focusProfileId
            delay(1800)
        } finally {
            highlightedProfileId = null
            onProfileFocusHandled(focusProfileId)
        }
    }
    val busyProfileIds by viewModel.busyProfileIds.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val importDraft by viewModel.importDraft.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importedProfileId by viewModel.importedProfileId.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val batchConfirmation by viewModel.batchConfirmation.collectAsStateWithLifecycle()
    val batchProgress by viewModel.batchProgress.collectAsStateWithLifecycle()
    val batchToast by viewModel.batchToast.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(batchToast) {
        batchToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeBatchToast()
        }
    }
    val selecting = selectedIds.isNotEmpty() || batchProgress != null
    LaunchedEffect(profiles) { viewModel.pruneSelection() }
    BackHandler(enabled = selecting) {
        if (batchProgress != null) viewModel.cancelBatch() else viewModel.clearSelection()
    }
    LaunchedEffect(importedProfileId) {
        importedProfileId?.let { id ->
            viewModel.onEditorOpened()
            onEditProfile(id)
        }
    }
    var newDialog by rememberSaveable { mutableStateOf(false) }
    var importChoiceDialog by rememberSaveable { mutableStateOf(false) }
    var deleteCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openFileImport(uri)
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FrpUiTokens.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FrpIconButton(label = stringResource(R.string.profiles_exit_selection),
                    enabled = batchProgress == null, onClick = viewModel::clearSelection) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
                Text(stringResource(R.string.profiles_selected, selectedIds.size), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val allSelected = profiles.isNotEmpty() && selectedIds.size == profiles.size
                FrpIconButton(label = stringResource(R.string.profiles_select_all),
                    enabled = batchProgress == null && !allSelected,
                    onClick = viewModel::selectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = null)
                }
                ProfileBatchAction.entries.forEach { action ->
                    FrpIconButton(
                        label = batchActionLabel(action),
                        onClick = { viewModel.prepareBatch(action) },
                        enabled = selectedIds.isNotEmpty() && batchProgress == null && busyProfileIds.isEmpty(),
                    ) {
                        Icon(when (action) {
                            ProfileBatchAction.EnableAutoStart -> Icons.Rounded.CheckCircleOutline
                            ProfileBatchAction.DisableAutoStart -> Icons.Rounded.Block
                            ProfileBatchAction.Delete -> Icons.Rounded.Delete
                        }, contentDescription = null)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(FrpUiTokens.ScreenPadding).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
            ) {
                Button(onClick = { newDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profiles_new))
                }
                FilledTonalButton(onClick = { importChoiceDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profiles_import))
                }
            }
        }

        batchProgress?.let { progress ->
            Column(Modifier.padding(horizontal = FrpUiTokens.ScreenPadding)) {
                LinearWavyProgressIndicator(progress = { progress.completed.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.profiles_batch_progress, progress.completed, progress.total),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancelBatch, enabled = !progress.cancelling && progress.completed < progress.total) {
                        Text(stringResource(if (progress.cancelling) R.string.profiles_batch_cancelling else android.R.string.cancel))
                    }
                }
            }
        }

        if (profileSnapshot == null) {
            CircularWavyProgressIndicator(Modifier.padding(FrpUiTokens.ScreenPadding))
        } else if (profiles.isEmpty()) {
            EmptyState(text = stringResource(R.string.profiles_empty))
        }
        error?.let { message ->
            SelectionContainer(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                ErrorText(
                    text = message,
                    modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = FrpUiTokens.ListSpacing),
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
        ) {
            items(profiles, key = { it.id }) { profile ->
                val busy = profile.id in busyProfileIds || batchProgress != null
                val selected = profile.id in selectedIds
                val containerColor by animateColorAsState(
                    when {
                        profile.id == highlightedProfileId -> FrpUiTokens.HighlightColor
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    animationSpec = tween(350),
                    label = "profileHighlight",
                )
                FrpListRow(
                    modifier = Modifier
                        .padding(horizontal = FrpUiTokens.ScreenPadding)
                        .semantics { this.selected = selected }
                        .combinedClickable(
                            enabled = !busy,
                            role = if (selecting) Role.Checkbox else Role.Button,
                            onLongClickLabel = stringResource(R.string.profiles_select),
                            onLongClick = { viewModel.toggleSelection(profile.id) },
                            onClick = { if (selecting) viewModel.toggleSelection(profile.id) else onEditProfile(profile.id) },
                        ),
                    containerColor = containerColor,
                    icon = if (profile.type == FrpType.Client) Icons.Rounded.CloudSync else Icons.Rounded.Dns,
                    title = profile.name,
                    subtitle = "${if (profile.type == FrpType.Client) "frpc" else "frps"} · ${
                        stringResource(
                            if (profile.autoStart) {
                                R.string.profiles_auto_start
                            } else {
                                R.string.profiles_manual_start
                            },
                        )
                    }",
                    trailing = {
                        if (selecting) Checkbox(checked = selected, onCheckedChange = null, enabled = !busy)
                        else {
                            Row {
                                Switch(
                                    checked = profile.autoStart,
                                    enabled = !busy && !profile.storageError,
                                    onCheckedChange = { viewModel.toggleAutoStart(profile, it) },
                                )
                                FrpIconButton(
                                    label = stringResource(R.string.profiles_delete),
                                    onClick = { deleteCandidateId = profile.id },
                                    enabled = !busy,
                                ) {
                                    if (busy) {
                                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(Icons.Rounded.Delete, contentDescription = null)
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    batchConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { viewModel.batchConfirmation.value = null },
            title = { Text(batchActionLabel(confirmation.action)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(pluralStringResource(when (confirmation.action) {
                        ProfileBatchAction.EnableAutoStart -> R.plurals.profiles_batch_enable_message
                        ProfileBatchAction.DisableAutoStart -> R.plurals.profiles_batch_disable_message
                        ProfileBatchAction.Delete -> R.plurals.profiles_batch_delete_message
                    }, confirmation.profiles.size, confirmation.profiles.size))
                    Text(confirmation.profiles.joinToString("\n") { it.name })
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmBatch) { Text(batchActionLabel(confirmation.action)) } },
            dismissButton = { TextButton(onClick = { viewModel.batchConfirmation.value = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (newDialog) {
        ChoiceDialog(
            title = stringResource(R.string.profiles_new),
            onDismiss = { newDialog = false },
            entries = listOf(
                stringResource(R.string.profiles_new_frpc) to {
                    onNewProfile(FrpType.Client)
                },
                stringResource(R.string.profiles_new_frps) to {
                    onNewProfile(FrpType.Server)
                },
            ),
        )
    }

    if (importChoiceDialog) {
        ProfileActionDialog(
            title = stringResource(R.string.profiles_import),
            onDismiss = { importChoiceDialog = false },
            entries = listOf(
                stringResource(R.string.profiles_import_file) to {
                    importChoiceDialog = false
                    documentLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                },
                stringResource(R.string.profiles_paste_import) to {
                    importChoiceDialog = false
                    viewModel.openPasteImport()
                },
            ),
        )
    }

    importDraft?.let { draft ->
        ImportTomlDialog(
            draft = draft,
            importing = importing,
            error = error,
            onChange = viewModel::updateImportDraft,
            onDismiss = viewModel::dismissImport,
            onImport = viewModel::confirmImport,
        )
    }

    profiles.firstOrNull { it.id == deleteCandidateId }?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteCandidateId = null },
            title = { Text(stringResource(R.string.profiles_delete)) },
            text = { Text(stringResource(R.string.profiles_delete_message, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(profile)
                        deleteCandidateId = null
                    },
                ) {
                    Text(stringResource(R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidateId = null }) {
                    Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun batchActionLabel(action: ProfileBatchAction): String = stringResource(when (action) {
    ProfileBatchAction.EnableAutoStart -> R.string.profiles_enable_auto_start
    ProfileBatchAction.DisableAutoStart -> R.string.profiles_disable_auto_start
    ProfileBatchAction.Delete -> R.string.profiles_delete
})

@Composable
private fun ProfileActionDialog(
    title: String,
    onDismiss: () -> Unit,
    entries: List<Pair<String, () -> Unit>>,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                entries.forEach { (label, action) ->
                    ListItem(
                        modifier = Modifier.clickable(onClick = action),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun readToml(context: Context, uri: Uri): String =
    requireNotNull(context.contentResolver.openInputStream(uri)) { "FILE_OPEN_FAILED" }
        .use { it.readLimited(1024 * 1024).toString(Charsets.UTF_8) }

@Composable
private fun ImportTomlDialog(
    draft: ProfileImportDraft,
    importing: Boolean,
    error: String?,
    onChange: (ProfileImportDraft) -> Unit,
    onDismiss: () -> Unit,
    onImport: (FrpType, String) -> Unit,
) {
    val validator = remember { TomlValidator() }
    var suggestedType by rememberSaveable { mutableStateOf(FrpType.Client) }
    val type = draft.type ?: suggestedType
    LaunchedEffect(draft.toml, draft.type) {
        if (draft.type != null) return@LaunchedEffect
        delay(200)
        suggestedType = withContext(Dispatchers.Default) { validator.suggestType(draft.toml) } ?: FrpType.Client
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_import)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = type == FrpType.Client,
                        onClick = { onChange(draft.copy(type = FrpType.Client)) },
                        enabled = !importing,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("frpc") }
                    SegmentedButton(
                        selected = type == FrpType.Server,
                        onClick = { onChange(draft.copy(type = FrpType.Server)) },
                        enabled = !importing,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("frps") }
                }
                OutlinedTextField(
                    value = draft.toml,
                    onValueChange = { onChange(draft.copy(toml = it)) },
                    enabled = !importing,
                    minLines = 8,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { ErrorText(it, Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(type, draft.toml) },
                enabled = draft.toml.isNotBlank() && !importing,
            ) {
                if (importing) CircularWavyProgressIndicator(Modifier.size(24.dp))
                else Text(stringResource(R.string.profiles_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
            }
        },
    )
}
