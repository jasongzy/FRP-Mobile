package com.jasongzy.frpmobile.ui.settings

import com.jasongzy.frpmobile.R
import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.ConfigBackup
import com.jasongzy.frpmobile.core.frp.ModuleStatus
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import com.jasongzy.frpmobile.ui.components.SectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MaintenanceState(
    val busy: Boolean = false,
    val moduleChecking: Boolean = false,
    val backup: ConfigBackup? = null,
    val module: ModuleStatus? = null,
    val error: String? = null,
    val toastMessageRes: Int? = null,
)

class MaintenanceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val resolver = application.contentResolver
    val state = MutableStateFlow(MaintenanceState())

    private fun execute(action: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(
                    error = e.message ?: e.javaClass.simpleName,
                    toastMessageRes = R.string.settings_operation_failed_short,
                ) }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    fun export(uri: Uri) = execute {
        val data = repository.exportBackup()
        checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(data) }
        state.update { it.copy(toastMessageRes = R.string.settings_backup_exported) }
    }

    fun inspect(uri: Uri) = execute {
        val backup = repository.readBackup(checkNotNull(resolver.openInputStream(uri)))
        state.update { it.copy(backup = backup) }
    }

    fun restore(replace: Boolean) {
        val backup = state.value.backup ?: return
        execute {
            val result = repository.restoreBackup(backup, replace)
            check(result.isSuccess) { result.message }
            state.update { it.copy(backup = null, toastMessageRes = R.string.settings_backup_imported) }
        }
    }

    fun refreshModule() {
        if (state.value.busy || state.value.moduleChecking) return
        state.update { it.copy(moduleChecking = true) }
        viewModelScope.launch {
            try {
                val result = repository.moduleStatus()
                state.update { it.copy(module = result) }
            } finally {
                state.update { it.copy(moduleChecking = false) }
            }
        }
    }

    fun module(action: String) {
        if (state.value.moduleChecking) return
        execute {
            val result = repository.manageModule(action)
            state.update { it.copy(
                module = result,
                toastMessageRes = if (result.error == null) R.string.settings_operation_done else R.string.settings_operation_failed_short,
            ) }
        }
    }

    fun dismissBackup() { if (!state.value.busy) state.update { it.copy(backup = null) } }
    fun dismissError() { state.update { it.copy(error = null) } }
    fun consumeToast() { state.update { it.copy(toastMessageRes = null) } }
}

@Composable
internal fun MaintenanceSettings(viewModel: MaintenanceViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.toastMessageRes) {
        state.toastMessageRes?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }
    val module = state.module
    val moduleBusy = state.busy || state.moduleChecking
    var exportConfirm by rememberSaveable { mutableStateOf(false) }
    var moduleDialog by rememberSaveable { mutableStateOf(false) }
    var moduleAction by rememberSaveable { mutableStateOf<String?>(null) }
    var replace by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::export)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::inspect)
    }
    Column {
        SectionTitle(stringResource(R.string.settings_data_section))
        FrpListRow(
            icon = Icons.Rounded.FileUpload,
            title = stringResource(R.string.settings_export),
            subtitle = stringResource(R.string.settings_backup_contents),
            modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable(enabled = !state.busy) { exportConfirm = true },
        )
        FrpListRow(
            icon = Icons.Rounded.FileDownload,
            title = stringResource(R.string.settings_import),
            subtitle = stringResource(R.string.settings_backup_contents),
            modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = 8.dp).clickable(enabled = !state.busy) {
                import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
        )
        if (state.busy && !moduleDialog) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        AutomationSettings()
        FrpListRow(
            icon = Icons.Rounded.Security,
            title = stringResource(R.string.settings_magisk),
            subtitle = when {
                module == null -> stringResource(R.string.settings_module_checking)
                module.error != null -> stringResource(R.string.settings_module_unavailable)
                !module.installed -> stringResource(R.string.settings_module_not_installed)
                else -> listOfNotNull(
                    stringResource(R.string.settings_module_installed, moduleVersionText(module.version, module.versionCode)),
                    if (!module.enabled) stringResource(R.string.settings_module_disabled) else null,
                    if (module.needsReinstall) stringResource(R.string.settings_module_reinstall_required) else null,
                ).joinToString(" · ")
            },
            modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable(enabled = !state.busy) {
                moduleDialog = true
                viewModel.refreshModule()
            },
            trailing = {
                if (state.moduleChecking) CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
            },
        )
    }

    if (exportConfirm) AlertDialog(
        onDismissRequest = { exportConfirm = false },
        title = { Text(stringResource(R.string.settings_export)) },
        text = { Text(stringResource(R.string.settings_export_warning)) },
        confirmButton = { TextButton(onClick = {
            exportConfirm = false
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
            export.launch("FRP-Mobile-backup-$timestamp.json")
        }) { Text(stringResource(R.string.settings_export)) } },
        dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    state.backup?.let { backup ->
        AlertDialog(
            onDismissRequest = viewModel::dismissBackup,
            title = { Text(stringResource(R.string.settings_import)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    Text(stringResource(R.string.settings_import_warning, backup.profiles.size))
                    listOf(false to R.string.settings_merge, true to R.string.settings_replace).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(replace == value, enabled = !state.busy, role = Role.RadioButton,
                                onClick = { replace = value }).padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(replace == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(label))
                        }
                    }
                    Text(stringResource(if (replace) R.string.settings_replace_hint else R.string.settings_merge_hint),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { viewModel.restore(replace) }) {
                Text(stringResource(R.string.settings_import))
            } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = viewModel::dismissBackup) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (moduleDialog) AlertDialog(
        onDismissRequest = { if (!state.busy) moduleDialog = false },
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_magisk), modifier = Modifier.weight(1f))
                FrpIconButton(
                    label = stringResource(R.string.settings_check_again),
                    onClick = viewModel::refreshModule,
                    enabled = !moduleBusy,
                    tooltipBelow = true,
                ) {
                    if (moduleBusy) CircularWavyProgressIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings_root_boundary))
                if (module != null) {
                    if (module.error != null) SelectionContainer { Text(module.error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    else {
                        Text(stringResource(when {
                            module.running -> R.string.settings_module_running
                            module.enabled -> R.string.settings_module_idle
                            module.installed -> R.string.settings_module_stopped
                            else -> R.string.settings_module_ready
                        }), modifier = Modifier.padding(vertical = 12.dp))
                        val unknown = stringResource(R.string.settings_module_version_unknown)
                        if (module.installed) {
                            Text(stringResource(R.string.settings_module_version,
                                moduleVersionText(module.version, module.versionCode), module.frpVersion.ifBlank { unknown }))
                        }
                        Text(stringResource(R.string.settings_module_bundled_version,
                            moduleVersionText(module.bundledVersion, module.bundledVersionCode), module.bundledFrpVersion))
                        if (module.needsReinstall) {
                            Text(stringResource(R.string.settings_module_needs_reinstall),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(modifier = Modifier.fillMaxWidth(), enabled = !moduleBusy, onClick = { moduleAction = "install" }) {
                                Text(stringResource(if (module.installed) R.string.settings_module_reinstall else R.string.settings_module_install))
                            }
                            if (module.installed) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !moduleBusy,
                                    onClick = { moduleAction = if (module.enabled) "stop" else "start" },
                                ) {
                                    Text(stringResource(if (module.enabled) R.string.settings_module_stop else R.string.settings_module_start))
                                }
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !moduleBusy,
                                    onClick = { moduleAction = "uninstall" },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text(stringResource(R.string.settings_module_uninstall))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { moduleDialog = false }) { Text(stringResource(android.R.string.ok)) } },
    )
    moduleAction?.let { action ->
        AlertDialog(
            onDismissRequest = { moduleAction = null },
            title = { Text(stringResource(R.string.settings_magisk)) },
            text = { Text(stringResource(when (action) {
                "stop", "uninstall" -> R.string.settings_module_remove_warning
                else -> R.string.settings_module_takeover_warning
            })) },
            confirmButton = { TextButton(enabled = !moduleBusy, onClick = { moduleAction = null; viewModel.module(action) }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { moduleAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { SelectionContainer {
                Text(stringResource(R.string.settings_operation_failed, error), Modifier.verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun moduleVersionText(name: String, code: Int): String = when {
    name.isNotBlank() && code > 0 -> "$name ($code)"
    name.isNotBlank() -> name
    code > 0 -> stringResource(R.string.settings_module_version_code, code)
    else -> stringResource(R.string.settings_module_version_unknown)
}
