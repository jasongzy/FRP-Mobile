package com.jasongzy.frpmobile.ui.settings

import com.jasongzy.frpmobile.integration.AUTO_START_TARGET
import com.jasongzy.frpmobile.R
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.FrpSettings
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.runtime.AutomationReceiver
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import com.jasongzy.frpmobile.ui.components.SectionTitle
import com.jasongzy.frpmobile.integration.home.HomeShortcutsDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FrpSettings())
    val profiles = repository.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val error = MutableStateFlow<String?>(null)
    val toastMessageRes = MutableStateFlow<Int?>(null)
    val busy = MutableStateFlow(false)

    private fun execute(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
                toastMessageRes.value = R.string.settings_operation_failed_short
            }
            finally { busy.value = false }
        }
    }

    fun enable(value: Boolean) = execute { repository.setAutomationEnabled(value) }
    fun selectTile(id: String) = execute { repository.setTileProfileId(id) }
    fun allowRead(value: Boolean) = execute { repository.setExternalReadEnabled(value) }
    fun allowWrite(value: Boolean) = execute { repository.setExternalWriteEnabled(value) }
    suspend fun generateCommand(command: AutomationCommand): String {
        check(command.profileId.isEmpty() || repository.getProfile(command.profileId) != null) { "PROFILE_MISSING" }
        val token = repository.automationToken()
        check(token.isNotBlank()) { "ACCESS_DENIED" }
        return command.toAdbCommand(getApplication<Application>().packageName, token)
    }

    fun copyCommand(command: AutomationCommand, preview: String) = execute {
        check(generateCommand(command) == preview) { "AUTHORIZATION_CHANGED" }
        val app = getApplication<Application>()
        val clip = ClipData.newPlainText("FRP Mobile", preview).apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toastMessageRes.value = R.string.settings_command_copied
    }

    fun consumeToast() { toastMessageRes.value = null }
}

@Composable
internal fun AutomationSettings(viewModel: AutomationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val toastMessageRes by viewModel.toastMessageRes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(toastMessageRes) {
        toastMessageRes?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }
    var tileDialog by rememberSaveable { mutableStateOf(false) }
    var shortcutsDialog by rememberSaveable { mutableStateOf(false) }
    var automationDialog by rememberSaveable { mutableStateOf(false) }
    var commandDialog by rememberSaveable { mutableStateOf(false) }
    var accessConfirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val automationEnabled = settings.automationToken.isNotEmpty()
    val automationLabel = stringResource(R.string.settings_automation)
    val helpLabel = stringResource(R.string.settings_automation_help)
    Column {
        SectionTitle(stringResource(R.string.settings_system_control))
        if (Build.VERSION.SDK_INT >= 25) FrpListRow(
            icon = Icons.Rounded.TouchApp, title = stringResource(R.string.home_shortcuts),
            subtitle = stringResource(R.string.home_shortcuts_summary),
            modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).padding(bottom = 8.dp)
                .clickable { shortcutsDialog = true },
        )
        FrpListRow(
            icon = Icons.Rounded.ToggleOn, title = stringResource(R.string.settings_tile),
            subtitle = profiles.firstOrNull { it.id == settings.tileProfileId }?.name ?: stringResource(R.string.settings_choose_profile),
            modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable(enabled = !busy) { tileDialog = true },
        )
        FrpListRow(
            icon = Icons.Rounded.Autorenew, title = automationLabel,
            subtitle = stringResource(if (automationEnabled) R.string.settings_enabled else R.string.settings_disabled),
            modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = 8.dp),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsHelpButton { automationDialog = true }
                    Switch(
                        checked = automationEnabled,
                        onCheckedChange = viewModel::enable,
                        enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = automationLabel },
                    )
                }
            },
        )
        ToggleItem(
            icon = Icons.Rounded.Description, title = stringResource(R.string.settings_external_read),
            checked = settings.externalReadEnabled,
            enabled = !busy,
            onChange = { if (it) accessConfirmation = "read" else viewModel.allowRead(false) },
            helpText = stringResource(R.string.settings_external_read_help),
        )
        ToggleItem(
            icon = Icons.Rounded.EditNote, title = stringResource(R.string.settings_external_write),
            checked = settings.externalWriteEnabled,
            enabled = !busy,
            onChange = { if (it) accessConfirmation = "write" else viewModel.allowWrite(false) },
            helpText = stringResource(R.string.settings_external_write_help),
        )
    }
    if (tileDialog) ProfileChoiceDialog(profiles, settings.tileProfileId, onDismiss = { tileDialog = false }) { id ->
        viewModel.selectTile(id)
        tileDialog = false
    }
    if (shortcutsDialog && Build.VERSION.SDK_INT >= 25) HomeShortcutsDialog(profiles) { shortcutsDialog = false }
    if (commandDialog) AutomationCommandDialog(
        profiles = profiles,
        authorization = settings.automationToken,
        busy = busy,
        generateCommand = viewModel::generateCommand,
        onCopy = viewModel::copyCommand,
        onDismiss = { commandDialog = false },
    )
    if (automationDialog) AlertDialog(
        onDismissRequest = { automationDialog = false },
        title = { Text(helpLabel) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.settings_automation_help_text))
                    listOf(
                        R.string.settings_automation_tasker_title to stringResource(R.string.settings_automation_tasker_text),
                        R.string.settings_automation_copy_title to stringResource(R.string.settings_automation_copy_text),
                        R.string.settings_automation_targets_title to stringResource(R.string.settings_automation_targets_text),
                        R.string.settings_automation_examples_title to stringResource(R.string.settings_automation_examples_text),
                        R.string.settings_automation_intent_title to stringResource(
                            R.string.settings_automation_intent_text,
                            AutomationReceiver.ACTION,
                            LocalContext.current.packageName,
                            AutomationReceiver::class.java.name,
                        ),
                        R.string.settings_automation_access_title to stringResource(R.string.settings_automation_access_text),
                    ).forEach { (title, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                            Text(body)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { automationDialog = false }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(enabled = !busy && automationEnabled, onClick = {
                automationDialog = false
                commandDialog = true
            }) { Text(stringResource(R.string.settings_copy_command)) }
        },
    )
    if (accessConfirmation != null) AlertDialog(
        onDismissRequest = { accessConfirmation = null },
        title = { Text(stringResource(if (accessConfirmation == "read") R.string.settings_external_read else R.string.settings_external_write)) },
        text = {
            Text(
                stringResource(if (accessConfirmation == "read") R.string.settings_external_read_help else R.string.settings_external_write_help),
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (accessConfirmation == "read") viewModel.allowRead(true) else viewModel.allowWrite(true)
                accessConfirmation = null
            }) { Text(stringResource(R.string.settings_allow_access)) }
        },
        dismissButton = {
            TextButton(onClick = { accessConfirmation = null }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
    if (error != null) AlertDialog(
        onDismissRequest = { viewModel.error.value = null },
        text = { SelectionContainer {
            Text(stringResource(R.string.settings_operation_failed, error.orEmpty()), Modifier.verticalScroll(rememberScrollState()))
        } },
        confirmButton = { TextButton(onClick = { viewModel.error.value = null }) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
internal fun ProfileChoiceDialog(
    profiles: List<FrpProfile>, selected: String, onDismiss: () -> Unit,
    includeAutoStart: Boolean = false, onSelect: (String) -> Unit,
) {
    val defaultTarget = if (includeAutoStart) AUTO_START_TARGET else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_choose_profile)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                item(key = defaultTarget) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected == defaultTarget, role = Role.RadioButton,
                            onClick = { onSelect(defaultTarget) }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == defaultTarget, onClick = null)
                        Text(stringResource(if (includeAutoStart) R.string.settings_target_autostart else R.string.settings_tile_no_profile),
                            Modifier.padding(start = 12.dp))
                    }
                }
                items(profiles, key = { it.id }) { profile ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected == profile.id, role = Role.RadioButton,
                            onClick = { onSelect(profile.id) }).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == profile.id, onClick = null)
                        Text(profile.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
