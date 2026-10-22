package com.jasongzy.frpmobile.integration

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jasongzy.frpmobile.R
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.ui.components.ChoiceDialog
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.settings.ProfileChoiceDialog

internal const val AUTO_START_TARGET = "@autoStart"

internal fun operationLabel(operation: String) = when (operation) {
    "toggle" -> R.string.tasker_toggle
    "start" -> R.string.tasker_start
    "stop" -> R.string.tasker_stop
    "restart" -> R.string.tasker_restart
    "stopAll" -> R.string.tasker_stop_all
    else -> error("INVALID_OPERATION")
}

internal fun operationIcon(operation: String) = when (operation) {
    "toggle" -> Icons.Rounded.PowerSettingsNew
    "start" -> Icons.Rounded.PlayArrow
    "restart" -> Icons.Rounded.Refresh
    else -> Icons.Rounded.Stop
}

internal fun controlCommand(operation: String, selectedId: String) = when {
    operation == "stopAll" -> AutomationCommand(operation)
    selectedId == AUTO_START_TARGET -> AutomationCommand(operation, target = "autoStart")
    else -> AutomationCommand(operation, selectedId)
}

internal val AutomationCommand.selectedId get() = if (target == "autoStart") AUTO_START_TARGET else profileId

internal fun AutomationCommand.targetName(context: Context, profileName: String?): String = when {
    operation == "stopAll" -> context.getString(R.string.settings_target_all)
    target == "autoStart" -> context.getString(R.string.settings_target_autostart)
    else -> profileName ?: context.getString(R.string.home_profile_missing)
}

@Composable
internal fun ControlActionFields(
    profiles: List<FrpProfile>, operation: String, selectedId: String,
    onOperation: (String) -> Unit, onTarget: (String) -> Unit, enabled: Boolean = true,
) {
    var chooseOperation by rememberSaveable { mutableStateOf(false) }
    var chooseTarget by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FrpListRow(operationIcon(operation), stringResource(R.string.tasker_operation),
            subtitle = stringResource(operationLabel(operation)),
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { chooseOperation = true })
        if (operation != "stopAll") FrpListRow(Icons.Rounded.Tune, stringResource(R.string.settings_choose_profile),
            subtitle = if (selectedId == AUTO_START_TARGET) stringResource(R.string.settings_target_autostart)
                else profiles.firstOrNull { it.id == selectedId }?.name ?: stringResource(R.string.settings_choose_profile),
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { chooseTarget = true })
    }
    if (chooseOperation) ChoiceDialog(stringResource(R.string.tasker_operation),
        onDismiss = { chooseOperation = false }, selected = stringResource(operationLabel(operation)),
        entries = AutomationCommand.operations.map { stringResource(operationLabel(it)) to { onOperation(it) } })
    if (chooseTarget && operation != "stopAll") ProfileChoiceDialog(profiles, selectedId, includeAutoStart = true,
        onDismiss = { chooseTarget = false }) {
        onTarget(it)
        chooseTarget = false
    }
}
