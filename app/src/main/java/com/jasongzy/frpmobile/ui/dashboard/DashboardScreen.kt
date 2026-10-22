package com.jasongzy.frpmobile.ui.dashboard

import com.jasongzy.frpmobile.R
import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.FrpSettings
import com.jasongzy.frpmobile.core.frp.FrpInstanceStatus
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpRuntimeState
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import com.jasongzy.frpmobile.core.runtime.FrpRuntimePermissions
import com.jasongzy.frpmobile.ui.components.ErrorText
import com.jasongzy.frpmobile.ui.components.EmptyState
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.jasongzy.frpmobile.core.frp.FrpResult

data class DashboardUiState(
    val loading: Boolean = true,
    val profiles: List<FrpProfile> = emptyList(),
    val states: List<FrpRuntimeState> = emptyList(),
    val settings: FrpSettings = FrpSettings(),
    val busyProfileIds: Set<String> = emptySet(),
    val stopAllBusy: Boolean = false,
    val actionError: String? = null,
)

data class DashboardFeedback(val messageRes: Int, val profileName: String = "")

private enum class PendingRuntimeAction {
    Start,
    Restart,
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    private val busyProfileIds = MutableStateFlow<Set<String>>(emptySet())
    private val stopAllBusy = MutableStateFlow(false)
    private val actionError = MutableStateFlow<String?>(null)
    private val feedback = Channel<DashboardFeedback>(Channel.BUFFERED)
    val feedbackEvents = feedback.receiveAsFlow()

    private val baseUiState = combine(
        repository.profiles,
        repository.runtimeStates,
        repository.settings,
    ) { profiles, states, settings ->
        DashboardUiState(loading = false, profiles = profiles, states = states, settings = settings)
    }.onStart {
        repository.syncRuntimeStates()
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseUiState,
        busyProfileIds,
        stopAllBusy,
        actionError,
    ) { state, busyIds, allBusy, error ->
        state.copy(busyProfileIds = busyIds, stopAllBusy = allBusy, actionError = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    private fun execute(profile: FrpProfile?, successRes: Int, failureRes: Int, operation: suspend () -> FrpResult) {
        val id = profile?.id
        if (stopAllBusy.value) return
        if (id == null) {
            stopAllBusy.value = true
        } else {
            if (id in busyProfileIds.value) return
            busyProfileIds.update { it + id }
        }
        actionError.value = null
        viewModelScope.launch {
            try {
                val result = operation()
                if (!result.isSuccess) actionError.value = result.message
                // Keep the progress indicator until Room's result reaches the rendered state.
                uiState.first { it.states == repository.runtimeStates.first() }
                feedback.send(DashboardFeedback(if (result.isSuccess) successRes else failureRes, profile?.name.orEmpty()))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                actionError.value = e.message ?: e.javaClass.simpleName
                feedback.send(DashboardFeedback(failureRes, profile?.name.orEmpty()))
            }
            finally {
                if (id == null) stopAllBusy.value = false
                else busyProfileIds.update { it - id }
            }
        }
    }

    fun stopAll(context: Context) = execute(null, R.string.dashboard_all_stopped, R.string.dashboard_stop_all_failed) {
        FrpForegroundService.stopAll(context).await()
    }
    fun start(context: Context, profile: FrpProfile) = execute(profile, R.string.dashboard_profile_started, R.string.dashboard_start_failed) {
        FrpForegroundService.startProfile(context, profile.id).await()
    }
    fun stop(context: Context, profile: FrpProfile) = execute(profile, R.string.dashboard_profile_stopped, R.string.dashboard_stop_failed) {
        FrpForegroundService.stopProfile(context, profile.id).await()
    }
    fun restart(context: Context, profile: FrpProfile) = execute(profile, R.string.dashboard_profile_restarted, R.string.dashboard_restart_failed) {
        FrpForegroundService.restartProfile(context, profile.id).await()
    }

}

@Composable
fun DashboardScreen(
    onOpenBootStartSettings: () -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator()
        }
        return
    }
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner, context, resources) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.feedbackEvents.collect { event ->
                Toast.makeText(context, resources.getString(event.messageRes, event.profileName), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pagerState = rememberPagerState(pageCount = { FrpType.entries.size })
    val headerScroll = rememberScrollState()
    var tabsHeight by remember { mutableIntStateOf(0) }
    val headerScrollConnection = remember(headerScroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0) Offset(0f, -headerScroll.dispatchRawDelta(-available.y)) else Offset.Zero
        }
    }
    val active = state.states.count { it.canStop }
    val running = state.states.count { it.state == FrpInstanceStatus.Running }
    val stopping = state.states.any { it.state == FrpInstanceStatus.Stopping }
    val failed = state.states.count { it.state == FrpInstanceStatus.Failed }
    val recoveryEnabled = state.settings.autoRetryEnabled || state.settings.networkReconnectEnabled
    var pendingStartProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRuntimeAction by rememberSaveable { mutableStateOf<PendingRuntimeAction?>(null) }
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val profile = state.profiles.firstOrNull { it.id == pendingStartProfileId }
        val action = pendingRuntimeAction
        pendingStartProfileId = null
        pendingRuntimeAction = null
        if (profile != null) {
            if (state.settings.rootMode || FrpRuntimePermissions.canStart(context)) {
                when (action) {
                    PendingRuntimeAction.Restart -> viewModel.restart(context, profile)
                    else -> viewModel.start(context, profile)
                }
            } else {
                Toast.makeText(context, R.string.dashboard_local_network_permission_required, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun runWithRuntimePermissions(profile: FrpProfile, action: PendingRuntimeAction) {
        val missing = FrpRuntimePermissions.missingPermissions(context, state.settings.rootMode)
        if (missing.isEmpty()) {
            when (action) {
                PendingRuntimeAction.Start -> viewModel.start(context, profile)
                PendingRuntimeAction.Restart -> viewModel.restart(context, profile)
            }
        } else {
            pendingStartProfileId = profile.id
            pendingRuntimeAction = action
            runtimePermissionsLauncher.launch(missing)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageHeight = (maxHeight - with(LocalDensity.current) { tabsHeight.toDp() }).coerceAtLeast(0.dp)
        Column(
            Modifier.fillMaxSize().nestedScroll(headerScrollConnection).verticalScroll(headerScroll),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing)) {
                state.actionError?.let { error ->
                    ErrorText(text = error, modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding))
                }
                RuntimeOverviewCard(
                    active = active,
                    running = running,
                    stopping = stopping,
                    failed = failed,
                    recoveryEnabled = recoveryEnabled,
                    rootMode = state.settings.rootMode,
                    busy = state.stopAllBusy,
                    onStopAll = { viewModel.stopAll(context) },
                )
                FrpListRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = stringResource(R.string.dashboard_boot_start),
                    subtitle = stringResource(if (state.settings.bootStartEnabled) R.string.dashboard_enabled else R.string.dashboard_disabled),
                    modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding)
                        .clickable(role = Role.Button, onClick = onOpenBootStartSettings),
                )
                Spacer(Modifier.height(FrpUiTokens.ListSpacing))
            }
            TypeTabs(pagerState, Modifier.onSizeChanged { tabsHeight = it.height })
            HorizontalPager(state = pagerState, modifier = Modifier.height(pageHeight).fillMaxWidth()) { page ->
                val profiles = state.profiles.filter { it.type == FrpType.entries[page] }
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = FrpUiTokens.ListSpacing),
                    verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
                ) {
                    if (profiles.isEmpty()) {
                        item { EmptyState(text = stringResource(R.string.dashboard_no_profiles)) }
                    }
                    items(profiles, key = { it.id }) { profile ->
                        ProfileRuntimeCard(
                            profile = profile,
                            state = state.states.firstOrNull { it.id == profile.id },
                            recoveryEnabled = recoveryEnabled,
                            busy = state.stopAllBusy || profile.id in state.busyProfileIds,
                            onClick = { onOpenProfile(profile.id) },
                            onStart = { runWithRuntimePermissions(profile, PendingRuntimeAction.Start) },
                            onStop = { viewModel.stop(context, profile) },
                            onRestart = { runWithRuntimePermissions(profile, PendingRuntimeAction.Restart) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTabs(pagerState: PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    SecondaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        modifier = modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
        containerColor = Color.Transparent,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorLayout { measurable, constraints, positions ->
                    val tab = positions[pagerState.currentPage]
                    val offset = (tab.left + tab.width * pagerState.currentPageOffsetFraction).roundToPx()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.placeRelative(offset, 0) }
                },
            )
        },
    ) {
        FrpType.entries.forEachIndexed { index, type ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(if (type == FrpType.Client) "frpc" else "frps") },
            )
        }
    }
}

@Composable
private fun RuntimeOverviewCard(
    active: Int,
    running: Int,
    stopping: Boolean,
    failed: Int,
    recoveryEnabled: Boolean,
    rootMode: Boolean,
    busy: Boolean,
    onStopAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                RuntimeStatus(
                    state = when {
                        running > 0 -> FrpInstanceStatus.Running
                        stopping -> FrpInstanceStatus.Stopping
                        failed > 0 -> FrpInstanceStatus.Failed
                        else -> FrpInstanceStatus.Stopped
                    },
                    waiting = recoveryEnabled && active > 0 && running == 0 && !stopping,
                    large = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (failed > 0) stringResource(R.string.dashboard_instances_summary, running, failed)
                    else stringResource(R.string.dashboard_running_count, running),
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = stringResource(if (rootMode) R.string.dashboard_magisk_mode else R.string.dashboard_normal_mode),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            FilledTonalButton(
                onClick = onStopAll,
                enabled = active > 0 && !busy,
            ) {
                if (busy) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.dashboard_stop_all))
                }
                Text(stringResource(R.string.dashboard_stop_all), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ProfileRuntimeCard(
    profile: FrpProfile,
    state: FrpRuntimeState?,
    recoveryEnabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val canStop = state?.canStop == true
    val stopping = state?.state == FrpInstanceStatus.Stopping
    val lastError = if (profile.storageError) stringResource(R.string.dashboard_storage_error) else state?.lastError
    FrpListRow(
        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding)
            .clickable(role = Role.Button, onClick = onClick),
        icon = if (profile.type == FrpType.Client) Icons.Rounded.CloudSync else Icons.Rounded.Dns,
        title = profile.name,
        subtitleContent = {
            RuntimeStatus(
                state = state?.state ?: FrpInstanceStatus.Stopped,
                waiting = recoveryEnabled && state?.desiredRunning == true && state.state != FrpInstanceStatus.Running && !stopping,
                prefix = if (profile.type == FrpType.Client) "frpc" else "frps",
            )
        },
        status = lastError,
        statusRunning = state?.state == FrpInstanceStatus.Running && lastError.isNullOrBlank(),
        trailing = {
            Row {
                FrpIconButton(
                    label = stringResource(if (canStop) R.string.dashboard_stop else R.string.dashboard_start),
                    onClick = if (canStop) onStop else onStart,
                    enabled = !busy && (canStop || !profile.storageError),
                ) {
                    if (busy) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            imageVector = if (canStop) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                        )
                    }
                }
                FrpIconButton(
                    label = stringResource(R.string.dashboard_restart),
                    onClick = onRestart,
                    enabled = !busy && !stopping && !profile.storageError,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                    )
                }
            }
        },
    )
}

@Composable
private fun RuntimeStatus(state: FrpInstanceStatus, large: Boolean = false, prefix: String = "", waiting: Boolean = false) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = if (waiting) MaterialTheme.colorScheme.tertiary else when (state) {
        FrpInstanceStatus.Running -> if (dark) Color(0xFF68DBA4) else Color(0xFF13794A)
        FrpInstanceStatus.Stopped, FrpInstanceStatus.Stopping -> if (dark) Color(0xFFE8BF6A) else Color(0xFF946100)
        FrpInstanceStatus.Failed -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 6.dp),
    ) {
        if (prefix.isNotEmpty()) Text(
            text = "$prefix ·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (waiting) Icons.Rounded.HourglassTop else when (state) {
                FrpInstanceStatus.Running -> Icons.Rounded.CheckCircle
                FrpInstanceStatus.Stopped -> Icons.Rounded.StopCircle
                FrpInstanceStatus.Stopping -> Icons.Rounded.HourglassTop
                FrpInstanceStatus.Failed -> Icons.Rounded.Error
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(if (large) 32.dp else 16.dp),
        )
        Text(
            text = stringResource(if (waiting) R.string.dashboard_waiting else when (state) {
                FrpInstanceStatus.Running -> R.string.dashboard_running
                FrpInstanceStatus.Stopped -> R.string.dashboard_stopped
                FrpInstanceStatus.Stopping -> R.string.dashboard_stopping
                FrpInstanceStatus.Failed -> R.string.dashboard_failed
            }),
            style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
            color = if (large && state == FrpInstanceStatus.Running) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
