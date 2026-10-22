package com.jasongzy.frpmobile.ui.logs

import com.jasongzy.frpmobile.R
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.frp.FrpLog
import com.jasongzy.frpmobile.ui.components.EmptyState
import com.jasongzy.frpmobile.ui.components.ErrorText
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogsFilter(
    val instanceId: String = "",
    val type: String = "",
    val level: String = "",
    val keyword: String = "",
    val maxUid: Long? = null,
) {
    val paused: Boolean get() = maxUid != null
}

data class LogsUiState(
    val filter: LogsFilter = LogsFilter(),
    val logs: List<FrpLog> = emptyList(),
    val clearing: Boolean = false,
    val error: String? = null,
)

private data class LogsTextFilter(val instanceId: String, val keyword: String)

private data class LogsImmediateFilter(
    val type: String,
    val level: String,
    val maxUid: Long?,
)

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val filter = MutableStateFlow(LogsFilter())
    private var pauseJob: Job? = null
    private val clearing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val toastMessageRes = MutableStateFlow<Int?>(null)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val logs = combine(
        filter
            .map { LogsTextFilter(it.instanceId, it.keyword) }
            .distinctUntilChanged()
            .debounce(300),
        filter
            .map { LogsImmediateFilter(it.type, it.level, it.maxUid) }
            .distinctUntilChanged(),
    ) { text, immediate ->
        LogsFilter(
            instanceId = text.instanceId,
            keyword = text.keyword,
            type = immediate.type,
            level = immediate.level,
            maxUid = immediate.maxUid,
        )
    }.flatMapLatest { current ->
        repository.observeLogs(
            instanceId = current.instanceId,
            type = current.type,
            level = current.level,
            keyword = current.keyword,
            limit = 150,
            maxUid = current.maxUid,
        ).let { if (current.paused) it.take(1) else it }
    }

    val uiState: StateFlow<LogsUiState> = combine(filter, logs, clearing, error) { current, currentLogs, busy, message ->
        LogsUiState(current, currentLogs, busy, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState())

    fun setInstanceId(value: String) = filter.update { it.copy(instanceId = value) }
    fun setKeyword(value: String) = filter.update { it.copy(keyword = value) }
    fun setLevel(value: String) = filter.update { it.copy(level = value) }
    fun setType(value: String) = filter.update { it.copy(type = value) }
    fun setPaused(value: Boolean) {
        pauseJob?.cancel()
        pauseJob = viewModelScope.launch {
            try {
                val maxUid = if (value) repository.latestLogId() else null
                filter.update { it.copy(maxUid = maxUid) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
                toastMessageRes.value = R.string.logs_pause_failed
            }
        }
    }
    fun clearLogs() {
        if (clearing.value) return
        clearing.value = true
        error.value = null
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                repository.clearLogs()
                filter.update { if (it.paused) it.copy(maxUid = 0) else it }
                toastMessageRes.value = R.string.logs_cleared
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
                toastMessageRes.value = R.string.logs_clear_failed
            }
            finally { clearing.value = false }
        }
    }

    fun consumeToast() { toastMessageRes.value = null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toastMessageRes by viewModel.toastMessageRes.collectAsStateWithLifecycle()
    LaunchedEffect(toastMessageRes) {
        toastMessageRes?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val timeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val copiedLogsText = stringResource(R.string.logs_copied_logs)
    val noLogsText = stringResource(R.string.logs_no_logs_to_copy)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = FrpUiTokens.ListSpacing),
                verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing)) {
                    OutlinedTextField(
                        value = state.filter.keyword,
                        onValueChange = viewModel::setKeyword,
                        label = { Text(stringResource(R.string.logs_keyword)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.filter.instanceId,
                        onValueChange = viewModel::setInstanceId,
                        label = { Text(stringResource(R.string.logs_instance)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("", "frp", "client", "server").forEach { type ->
                        FilterChip(
                            selected = state.filter.type == type,
                            onClick = { viewModel.setType(type) },
                            label = { Text(type.ifBlank { stringResource(R.string.logs_all) }) },
                        )
                    }
                    listOf("info", "warn", "error").forEach { level ->
                        FilterChip(
                            selected = state.filter.level == level,
                            onClick = { viewModel.setLevel(if (state.filter.level == level) "" else level) },
                            label = { Text(level) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.logs_pause_updates))
                        Switch(checked = state.filter.paused, onCheckedChange = viewModel::setPaused)
                    }
                    Spacer(Modifier.weight(1f))
                    FrpIconButton(
                        label = stringResource(R.string.logs_copy_logs),
                        onClick = {
                            val count = copyLogs(context, state.logs)
                            snackbarScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (count > 0) copiedLogsText.format(count) else noLogsText,
                                )
                            }
                        },
                        enabled = state.logs.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    }
                    FrpIconButton(
                        label = stringResource(R.string.logs_clear),
                        onClick = viewModel::clearLogs,
                        enabled = state.logs.isNotEmpty() && !state.clearing,
                    ) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                    }
                }
            }
            state.error?.let { ErrorText(it, Modifier.padding(horizontal = FrpUiTokens.ScreenPadding)) }
            if (state.logs.isEmpty()) {
                EmptyState(text = stringResource(R.string.logs_empty))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                itemsIndexed(
                    state.logs,
                    key = { index, log -> if (log.uid > 0) log.uid else "${log.time}-${log.instanceId}-$index" },
                    contentType = { _, _ -> "log" },
                ) { _, log ->
                    val levelColor = when (log.level) {
                        "error" -> MaterialTheme.colorScheme.error
                        "warn" -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
                            Color(0xFFE8BF6A)
                        } else {
                            Color(0xFF946100)
                        }
                        "debug", "trace" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> null
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = 6.dp),
                    ) {
                        Text(
                            text = log.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = levelColor ?: MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${log.level.uppercase()} · ${log.type}/${log.instanceId.ifBlank { "-" }} · ${timeFormatter.format(Date(log.time))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = levelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun copyLogs(context: Context, logs: List<FrpLog>): Int {
    if (logs.isEmpty()) return 0
    val text = logs.joinToString("\n") { it.message }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("frp logs", text))
    return logs.size
}
