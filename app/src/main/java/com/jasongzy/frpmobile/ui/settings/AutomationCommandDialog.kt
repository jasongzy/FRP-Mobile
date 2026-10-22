package com.jasongzy.frpmobile.ui.settings

import com.jasongzy.frpmobile.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.ui.components.ChoiceDialog
import com.jasongzy.frpmobile.ui.components.FrpListRow
import kotlinx.coroutines.CancellationException

@Composable
internal fun AutomationCommandDialog(
    profiles: List<FrpProfile>,
    authorization: String,
    busy: Boolean,
    generateCommand: suspend (AutomationCommand) -> String,
    onCopy: (AutomationCommand, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var operation by rememberSaveable { mutableStateOf("start") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var operationDialog by rememberSaveable { mutableStateOf(false) }
    var targetDialog by rememberSaveable { mutableStateOf(false) }
    val profile = profiles.firstOrNull { it.id == selectedId }
    val request = when {
        operation == "stopAll" -> AutomationCommand(operation)
        selectedId == AUTO_START_TARGET -> AutomationCommand(operation, target = "autoStart")
        profile != null -> AutomationCommand(operation, profile.id)
        else -> null
    }
    var preview by remember(request, authorization) { mutableStateOf("") }
    var error by remember(request, authorization) { mutableStateOf<String?>(null) }
    val loading = request != null && authorization.isNotEmpty() && preview.isEmpty() && error == null
    LaunchedEffect(request, authorization) {
        if (request != null && authorization.isNotEmpty()) {
            try {
                preview = generateCommand(request)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_command_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FrpListRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(R.string.tasker_operation),
                    subtitle = stringResource(operationLabel(operation)),
                    modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { operationDialog = true },
                )
                FrpListRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_choose_profile),
                    subtitle = when {
                        operation == "stopAll" -> stringResource(R.string.settings_target_all)
                        selectedId == AUTO_START_TARGET -> stringResource(R.string.settings_target_autostart)
                        else -> profile?.name ?: stringResource(R.string.settings_choose_profile)
                    },
                    modifier = Modifier.alpha(if (operation == "stopAll") 0.6f else 1f)
                        .clickable(enabled = !busy && operation != "stopAll", role = Role.Button) { targetDialog = true },
                )
                if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    when {
                        preview.isNotEmpty() -> SelectionContainer {
                            Text(
                                preview,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        error != null -> SelectionContainer {
                            Text(
                                stringResource(R.string.settings_operation_failed, error.orEmpty()),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        !loading -> Text(
                            stringResource(if (authorization.isEmpty()) R.string.settings_command_access_required else R.string.settings_command_choose_target),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && preview.isNotEmpty(), onClick = { request?.let { onCopy(it, preview) } }) {
                Text(stringResource(R.string.settings_copy_command))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (operationDialog) ChoiceDialog(
        title = stringResource(R.string.tasker_operation),
        selected = stringResource(operationLabel(operation)),
        entries = listOf("start", "stop", "restart", "stopAll").map { value ->
            stringResource(operationLabel(value)) to { operation = value }
        },
        onDismiss = { operationDialog = false },
    )
    if (targetDialog) ProfileChoiceDialog(
        profiles = profiles, selected = selectedId, includeAutoStart = true,
        onDismiss = { targetDialog = false },
    ) {
        selectedId = it
        targetDialog = false
    }
}
