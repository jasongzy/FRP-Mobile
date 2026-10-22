package com.jasongzy.frpmobile.ui.settings

import com.jasongzy.frpmobile.integration.AUTO_START_TARGET
import com.jasongzy.frpmobile.integration.ControlActionFields
import com.jasongzy.frpmobile.integration.controlCommand
import com.jasongzy.frpmobile.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
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
    var selectedId by rememberSaveable { mutableStateOf(AUTO_START_TARGET) }
    val profile = profiles.firstOrNull { it.id == selectedId }
    val request = if (operation == "stopAll" || selectedId == AUTO_START_TARGET || profile != null)
        controlCommand(operation, selectedId) else null
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
                ControlActionFields(profiles, operation, selectedId, { operation = it }, { selectedId = it }, !busy)
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
}
