package com.jasongzy.frpmobile.integration.home

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jasongzy.frpmobile.integration.AUTO_START_TARGET
import com.jasongzy.frpmobile.integration.ControlActionFields
import com.jasongzy.frpmobile.integration.controlCommand
import com.jasongzy.frpmobile.integration.operationIcon
import com.jasongzy.frpmobile.integration.operationLabel
import com.jasongzy.frpmobile.integration.selectedId
import com.jasongzy.frpmobile.integration.targetName
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.components.FrpListRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(25)
@Composable
internal fun HomeShortcutsDialog(profiles: List<FrpProfile>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var shortcuts by remember { mutableStateOf(HomeShortcuts.list(context)) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(profiles) { shortcuts = withContext(Dispatchers.IO) { HomeShortcuts.list(context) } }
    fun execute(success: Int, block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                shortcuts = HomeShortcuts.list(context)
                editing = null
                Toast.makeText(context, success, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.settings_operation_failed, e.message), Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_shortcuts)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_shortcuts_help, HomeShortcuts.limit(context)))
                shortcuts.forEach { shortcut ->
                    val action = decodeHomeAction(shortcut.intent?.getStringExtra(EXTRA_HOME_ACTION))
                    FrpListRow(action?.operation?.let(::operationIcon) ?: Icons.Rounded.PlayArrow,
                        action?.targetName(context, profiles.firstOrNull { it.id == action.profileId }?.name) ?: stringResource(R.string.home_profile_missing),
                        subtitle = action?.operation?.let { stringResource(operationLabel(it)) }.orEmpty(),
                        modifier = Modifier.clickable(enabled = !busy) { editing = shortcut.id },
                        trailing = {
                            FrpIconButton(stringResource(R.string.profiles_delete), enabled = !busy,
                                onClick = { execute(R.string.home_shortcut_removed) { HomeShortcuts.remove(context, shortcut.id) } }) {
                                Icon(Icons.Rounded.Delete, contentDescription = null)
                            }
                        })
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { editing = "" }) { Text(stringResource(R.string.home_add_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
    editing?.let { id ->
        val initial = shortcuts.firstOrNull { it.id == id }?.intent?.getStringExtra(EXTRA_HOME_ACTION)?.let(::decodeHomeAction)
            ?: controlCommand("toggle", AUTO_START_TARGET)
        var selectedId by rememberSaveable(id) { mutableStateOf(initial.selectedId) }
        var operation by rememberSaveable(id) { mutableStateOf(initial.operation) }
        var confirmDiscard by rememberSaveable(id) { mutableStateOf(false) }
        val profile = profiles.firstOrNull { it.id == selectedId }
        val canSave = !busy && (operation == "stopAll" || selectedId == AUTO_START_TARGET || profile != null)
        val hasChanges = operation != initial.operation || (operation != "stopAll" && selectedId != initial.selectedId)
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    if (hasChanges) confirmDiscard = true else editing = null
                }
            },
            title = { Text(stringResource(if (id.isEmpty()) R.string.home_add_action else R.string.home_edit_action)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlActionFields(profiles, operation, selectedId, { operation = it }, { selectedId = it }, !busy)
                    if (id.isEmpty() && shortcuts.size >= HomeShortcuts.limit(context)) Text(stringResource(R.string.home_shortcuts_limit))
                }
            },
            confirmButton = {
                TextButton(enabled = canSave && (id.isNotEmpty() || shortcuts.size < HomeShortcuts.limit(context)), onClick = {
                    execute(R.string.home_saved) {
                        HomeShortcuts.save(context, id.ifEmpty { null }, controlCommand(operation, selectedId), profile?.name)
                    }
                }) { Text(stringResource(R.string.home_save_menu)) }
            },
            dismissButton = {
                if (Build.VERSION.SDK_INT >= 26 && HomeShortcuts.canPin(context)) TextButton(enabled = canSave, onClick = {
                    try {
                        val accepted = HomeShortcuts.pin(context, controlCommand(operation, selectedId), profile?.name)
                        Toast.makeText(context, if (accepted) R.string.home_pin_requested else R.string.home_pin_failed, Toast.LENGTH_SHORT).show()
                        if (accepted) editing = null
                    } catch (e: Exception) {
                        Toast.makeText(context, resources.getString(R.string.settings_operation_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.home_pin)) }
            },
        )
        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.home_discard_changes_title)) },
            confirmButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) }
            },
        )
    }
}
