package com.jasongzy.frpmobile.integration.tasker

import com.jasongzy.frpmobile.ui.settings.AUTO_START_TARGET
import com.jasongzy.frpmobile.ui.settings.ProfileChoiceDialog
import com.jasongzy.frpmobile.ui.settings.operationLabel
import com.jasongzy.frpmobile.R
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.FrpSettings
import com.jasongzy.frpmobile.core.runtime.AutomationCommand
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import com.jasongzy.frpmobile.ui.components.ChoiceDialog
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.theme.FrpMobileTheme
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerActionNoOutput
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelperNoOutput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultError
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@TaskerInputRoot
class FrpTaskerInput @JvmOverloads constructor(
    @field:TaskerInputField("operation") var operation: String = "start",
    @field:TaskerInputField("profile_id") var profileId: String = "",
    @field:TaskerInputField("target") var target: String = "",
    @field:TaskerInputField("token", ignoreInStringBlurb = true) var token: String = "",
)

class FrpTaskerRunner : TaskerPluginRunnerActionNoOutput<FrpTaskerInput>() {
    override fun run(context: Context, input: TaskerInput<FrpTaskerInput>): TaskerPluginResult<Unit> = try {
        val result = runBlocking(Dispatchers.IO) {
            withTimeout(50_000) {
                val value = input.regular
                FrpForegroundService.control(context, AutomationCommand(value.operation, value.profileId, value.target), value.token).await()
            }
        }
        if (result.isSuccess) TaskerPluginResultSucess() else TaskerPluginResultError(1, result.message)
    } catch (e: Exception) {
        TaskerPluginResultError(1, e.message ?: "CONTROL_FAILED")
    }
}

private class FrpTaskerHelper(config: TaskerPluginConfig<FrpTaskerInput>) :
    TaskerPluginConfigHelperNoOutput<FrpTaskerInput, FrpTaskerRunner>(config) {
    override val runnerClass = FrpTaskerRunner::class.java
    override val inputClass = FrpTaskerInput::class.java
    override val defaultInput = FrpTaskerInput(target = "autoStart")
    override val addDefaultStringBlurb = false
    override fun addToStringBlurb(input: TaskerInput<FrpTaskerInput>, blurbBuilder: StringBuilder) {
        val value = input.regular
        blurbBuilder.append(context.getString(operationLabel(value.operation)))
        if (value.operation != "stopAll") {
            blurbBuilder.append(" · ").append(
                if (value.target == "autoStart") context.getString(R.string.settings_target_autostart) else value.profileId,
            )
        }
    }
}

class TaskerActivity : AppCompatActivity(), TaskerPluginConfig<FrpTaskerInput> {
    override val context: Context get() = this
    private val helper by lazy { FrpTaskerHelper(this) }
    private var operation by mutableStateOf("start")
    private var profileId by mutableStateOf("")
    private var target by mutableStateOf("autoStart")
    private var saving by mutableStateOf(false)
    private var token = ""
    override val inputForTasker get() = TaskerInput(FrpTaskerInput(operation, profileId, target, token))
    override fun assignFromInput(input: TaskerInput<FrpTaskerInput>) {
        operation = input.regular.operation
        profileId = input.regular.profileId
        target = input.regular.target
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        helper.onCreate()
        savedInstanceState?.let {
            operation = it.getString("operation", operation)
            profileId = it.getString("profile_id", profileId)
            target = it.getString("target", target)
        }
        val repository = AppGraph.repository(this)
        setContent {
            val settingsFlow = remember(repository) { repository.settings.map<FrpSettings, FrpSettings?> { it } }
            val currentSettings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val settings = currentSettings ?: return@setContent
            val profiles by repository.profiles.collectAsStateWithLifecycle(emptyList())
            var operationDialog by rememberSaveable { mutableStateOf(false) }
            var targetDialog by rememberSaveable { mutableStateOf(false) }
            FrpMobileTheme(
                seedColor = settings.themeSeedColor, mode = settings.themeMode, languageMode = settings.languageMode,
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.tasker_title)) },
                            navigationIcon = {
                                FrpIconButton(
                                    label = stringResource(android.R.string.cancel),
                                    onClick = { finish() },
                                    tooltipBelow = true,
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                                }
                            },
                            actions = {
                                TextButton(
                                    enabled = !saving && settings.automationToken.isNotEmpty() &&
                                        (operation == "stopAll" || target == "autoStart" || profiles.any { it.id == profileId }),
                                    onClick = {
                                        saving = true
                                        lifecycleScope.launch {
                                            try {
                                                AutomationCommand(operation, profileId, target)
                                                token = repository.automationToken()
                                                check(token.isNotEmpty()) { getString(R.string.tasker_access_required) }
                                                helper.finishForTasker()
                                            } catch (e: CancellationException) { throw e }
                                            catch (e: Exception) {
                                                Toast.makeText(this@TaskerActivity, e.message, Toast.LENGTH_LONG).show()
                                            } finally { saving = false }
                                        }
                                    },
                                ) { Text(stringResource(R.string.tasker_save)) }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                        Text(stringResource(R.string.tasker_help), Modifier.padding(vertical = 16.dp))
                        if (settings.automationToken.isEmpty()) Text(stringResource(R.string.tasker_access_required))
                        FrpListRow(
                            icon = Icons.Rounded.PlayArrow, title = stringResource(R.string.tasker_operation),
                            subtitle = stringResource(operationLabel(operation)),
                            modifier = Modifier.clickable(enabled = !saving) { operationDialog = true },
                        )
                        if (operation != "stopAll") FrpListRow(
                            icon = Icons.Rounded.Tune, title = stringResource(R.string.settings_choose_profile),
                            subtitle = if (target == "autoStart") stringResource(R.string.settings_target_autostart)
                                else profiles.firstOrNull { it.id == profileId }?.name ?: stringResource(R.string.settings_choose_profile),
                            modifier = Modifier.clickable(enabled = !saving) { targetDialog = true },
                        )
                    }
                }
                if (operationDialog) ChoiceDialog(
                    title = stringResource(R.string.tasker_operation), onDismiss = { operationDialog = false },
                    selected = stringResource(operationLabel(operation)),
                    entries = listOf("start", "stop", "restart", "stopAll").map { value ->
                        stringResource(operationLabel(value)) to {
                            operation = value
                            if (value == "stopAll") { profileId = ""; target = "" }
                            else if (profileId.isEmpty()) target = "autoStart"
                        }
                    },
                )
                if (targetDialog) ProfileChoiceDialog(
                    profiles, if (target == "autoStart") AUTO_START_TARGET else profileId,
                    onDismiss = { targetDialog = false }, includeAutoStart = true,
                ) { id ->
                    target = if (id == AUTO_START_TARGET) "autoStart" else ""
                    profileId = if (id == AUTO_START_TARGET) "" else id
                    targetDialog = false
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("operation", operation)
        outState.putString("profile_id", profileId)
        outState.putString("target", target)
        super.onSaveInstanceState(outState)
    }
}
