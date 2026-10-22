package com.jasongzy.frpmobile.ui.settings

import com.jasongzy.frpmobile.BuildConfig
import com.jasongzy.frpmobile.R
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewModelScope
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.LOG_RETENTION_DAYS
import com.jasongzy.frpmobile.core.data.FrpSettings
import com.jasongzy.frpmobile.core.frp.DEFAULT_THEME_SEED_COLOR
import com.jasongzy.frpmobile.core.frp.LanguageMode
import com.jasongzy.frpmobile.core.frp.ThemeMode
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import com.jasongzy.frpmobile.core.runtime.FrpRuntimePermissions
import com.jasongzy.frpmobile.ui.components.FrpListRow
import com.jasongzy.frpmobile.ui.components.ChoiceDialog
import com.jasongzy.frpmobile.ui.components.FrpUiTokens
import com.jasongzy.frpmobile.ui.components.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

private data class ThemeSeedOption(
    val color: Int,
    val labelRes: Int,
)

private val ThemeSeedOptions = listOf(
    ThemeSeedOption(DEFAULT_THEME_SEED_COLOR, R.string.settings_seed_teal),
    ThemeSeedOption(0xFF4D6BFE.toInt(), R.string.settings_seed_blue),
    ThemeSeedOption(0xFF6D5BD0.toInt(), R.string.settings_seed_violet),
    ThemeSeedOption(0xFF00875A.toInt(), R.string.settings_seed_green),
    ThemeSeedOption(0xFFC26A00.toInt(), R.string.settings_seed_amber),
    ThemeSeedOption(0xFFB3261E.toInt(), R.string.settings_seed_red),
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppGraph.repository(application)
    val settings: StateFlow<FrpSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FrpSettings())
    val error = MutableStateFlow<String?>(null)
    val recovering = MutableStateFlow(false)

    private fun execute(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = e.message ?: e.javaClass.simpleName }
    }

    fun setBootStart(enabled: Boolean) = execute { repository.setBootStartEnabled(enabled) }
    fun setLaunchStart(enabled: Boolean) = execute { repository.setLaunchStartEnabled(enabled) }
    fun setExcludeFromRecents(enabled: Boolean) = execute { repository.setExcludeFromRecents(enabled) }
    fun setNetworkReconnect(enabled: Boolean) = execute {
        repository.setNetworkReconnectEnabled(enabled)
    }
    fun setAutoRetry(enabled: Boolean) = execute {
        repository.setAutoRetryEnabled(enabled)
    }
    fun setScreenOffKeepAlive(enabled: Boolean) = execute {
        repository.setScreenOffKeepAliveEnabled(enabled)
    }
    fun setRetention(days: Int) = execute { repository.setLogRetentionDays(days) }
    fun setThemeMode(mode: ThemeMode) = execute { repository.setThemeMode(mode) }
    fun setThemeSeedColor(color: Int) = execute { repository.setThemeSeedColor(color) }
    fun setLanguage(mode: LanguageMode) = execute { repository.setLanguageMode(mode) }
    fun recoverPendingStart(context: Context) {
        if (recovering.value) return
        recovering.value = true
        execute {
            try {
                val result = FrpForegroundService.retryPendingStart(context).await()
                check(result.isSuccess) { result.message }
            } finally { recovering.value = false }
        }
    }
}

@Composable
fun SettingsScreen(
    focusBootStart: Boolean,
    onBootStartFocusHandled: () -> Unit,
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    maintenanceViewModel: MaintenanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val recovering by viewModel.recovering.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val githubUrl = stringResource(R.string.settings_github_url)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        maintenanceViewModel.refreshModule()
    }
    val listState = rememberLazyListState()
    val bootStartIndex by rememberUpdatedState(if (settings.pendingStart || recovering) 2 else 1)
    val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (settings.rootMode || FrpRuntimePermissions.canStart(context)) viewModel.recoverPendingStart(context)
        else Toast.makeText(context, R.string.settings_local_network_permission_required, Toast.LENGTH_SHORT).show()
    }
    var bootStartHighlighted by remember { mutableStateOf(false) }
    val bootStartColor by animateColorAsState(
        if (bootStartHighlighted) FrpUiTokens.HighlightColor else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(350),
        label = "bootStartHighlight",
    )
    LaunchedEffect(focusBootStart) {
        if (focusBootStart) {
            try {
                lifecycle.withResumed { }
                listState.animateScrollToItem(bootStartIndex)
                bootStartHighlighted = true
                delay(1800)
            } finally {
                bootStartHighlighted = false
                onBootStartFocusHandled()
            }
        }
    }
    var seedDialog by rememberSaveable { mutableStateOf(false) }
    var languageDialog by rememberSaveable { mutableStateOf(false) }

    var themeDialog by rememberSaveable { mutableStateOf(false) }
    if (error != null) AlertDialog(
        onDismissRequest = { viewModel.error.value = null },
        text = { Text(stringResource(R.string.settings_operation_failed, error.orEmpty())) },
        confirmButton = { TextButton(onClick = { viewModel.error.value = null }) { Text(stringResource(android.R.string.ok)) } },
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(FrpUiTokens.ListSpacing),
    ) {
        if (settings.pendingStart || recovering) {
            item {
                FrpListRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = stringResource(R.string.settings_pending_start),
                    subtitle = stringResource(R.string.settings_pending_start_hint),
                    modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable(enabled = !recovering) {
                        val missing = FrpRuntimePermissions.missingPermissions(context, settings.rootMode)
                        if (missing.isEmpty()) viewModel.recoverPendingStart(context)
                        else permissionsLauncher.launch(missing)
                    },
                    trailing = { if (recovering) CircularWavyProgressIndicator(Modifier.size(24.dp)) },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.settings_background_section)) }
        item(key = "boot_start") {
            ToggleItem(
                icon = Icons.Rounded.PowerSettingsNew,
                title = stringResource(R.string.settings_boot_start),
                checked = settings.bootStartEnabled,
                onChange = viewModel::setBootStart,
                containerColor = bootStartColor,
            )
        }
        item {
            ToggleItem(
                icon = Icons.AutoMirrored.Rounded.Launch,
                title = stringResource(R.string.settings_launch_start),
                checked = settings.launchStartEnabled,
                onChange = viewModel::setLaunchStart,
                helpText = stringResource(R.string.settings_launch_start_help),
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.settings_exclude_recents),
                checked = settings.excludeFromRecents,
                onChange = viewModel::setExcludeFromRecents,
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.Wifi,
                title = stringResource(R.string.settings_network_reconnect),
                checked = settings.networkReconnectEnabled,
                onChange = viewModel::setNetworkReconnect,
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.settings_auto_retry),
                checked = settings.autoRetryEnabled,
                onChange = viewModel::setAutoRetry,
                helpText = stringResource(R.string.settings_auto_retry_help),
            )
        }
        item {
            ToggleItem(
                icon = Icons.Rounded.ScreenLockPortrait,
                title = stringResource(R.string.settings_screen_off_keep_alive),
                checked = settings.screenOffKeepAliveEnabled,
                onChange = viewModel::setScreenOffKeepAlive,
                helpText = stringResource(R.string.settings_screen_off_keep_alive_help_text),
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.BatterySaver,
                title = stringResource(R.string.settings_battery),
                subtitle = stringResource(
                    if (batteryUnrestricted) {
                        R.string.settings_battery_allowed
                    } else {
                        R.string.settings_battery_restricted
                    },
                ),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openBatterySettings(context) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.TipsAndUpdates,
                title = stringResource(R.string.settings_autostart_guide),
                subtitle = stringResource(R.string.settings_autostart_guide_hint),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openAppDetails(context) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(R.string.settings_notifications_hint),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { openNotificationSettings(context) },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_appearance_section)) }
        item {
            FrpListRow(
                icon = Icons.Rounded.Brightness6,
                title = stringResource(R.string.settings_theme_mode),
                subtitle = themeModeLabel(settings.themeMode),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { themeDialog = true },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_theme_seed),
                subtitle = themeSeedLabel(settings.themeSeedColor),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { seedDialog = true },
                trailing = { SeedSwatch(settings.themeSeedColor) },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(settings.languageMode),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable { languageDialog = true },
            )
        }
        item { SectionTitle(stringResource(R.string.settings_logs_section)) }
        item {
            Surface(
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = FrpUiTokens.ScreenPadding, vertical = 12.dp)) {
                    val retention = remember(settings.logRetentionDays) {
                        SliderState(
                            value = settings.logRetentionDays.toFloat(),
                            trackRange = LOG_RETENTION_DAYS.first.toFloat()..LOG_RETENTION_DAYS.last.toFloat(),
                            steps = LOG_RETENTION_DAYS.last - LOG_RETENTION_DAYS.first - 1,
                        )
                    }
                    Text("${stringResource(R.string.settings_log_retention)}: ${retention.value.roundToInt()}")
                    Slider(
                        state = retention,
                        onValueChange = { retention.value = it },
                        onValueChangeFinished = { viewModel.setRetention(retention.value.roundToInt()) },
                    )
                }
            }
        }
        item { MaintenanceSettings(maintenanceViewModel) }
        item { SectionTitle(stringResource(R.string.settings_about_section)) }
        item {
            FrpListRow(
                icon = Icons.AutoMirrored.Rounded.Launch,
                title = stringResource(R.string.settings_github),
                subtitle = githubUrl.removePrefix("https://"),
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding).clickable(role = Role.Button) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.settings_cannot_open_link, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.settings_app_version),
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
            )
        }
        item {
            FrpListRow(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.settings_kernel_version),
                subtitle = BuildConfig.FRP_VERSION,
                modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
            )
        }
    }

    if (themeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme_mode),
            selected = themeModeLabel(settings.themeMode),
            onDismiss = { themeDialog = false },
            entries = ThemeMode.entries.map { mode -> themeModeLabel(mode) to { viewModel.setThemeMode(mode) } },
        )
    }

    if (seedDialog) {
        SeedColorDialog(
            selectedColor = settings.themeSeedColor,
            onSelect = viewModel::setThemeSeedColor,
            onDismiss = { seedDialog = false },
        )
    }
    if (languageDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_language),
            onDismiss = { languageDialog = false },
            selected = languageLabel(settings.languageMode),
            entries = listOf(
                stringResource(R.string.settings_system) to { viewModel.setLanguage(LanguageMode.System) },
                stringResource(R.string.settings_chinese) to { viewModel.setLanguage(LanguageMode.Chinese) },
                stringResource(R.string.settings_english) to { viewModel.setLanguage(LanguageMode.English) },
            ),
        )
    }
}

@Composable
private fun SeedSwatch(color: Int) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(color), CircleShape),
    )
}

@Composable
private fun SeedColorDialog(
    selectedColor: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.settings_theme_seed),
        selected = themeSeedLabel(selectedColor),
        onDismiss = onDismiss,
        entries = ThemeSeedOptions.map { option -> stringResource(option.labelRes) to { onSelect(option.color) } },
        leading = { label -> ThemeSeedOptions.firstOrNull { stringResource(it.labelRes) == label }?.let { SeedSwatch(it.color) } },
    )
}

@Composable
internal fun ToggleItem(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    helpText: String? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    FrpListRow(
        icon = icon,
        title = title,
        subtitle = stringResource(if (checked) R.string.settings_enabled else R.string.settings_disabled),
        modifier = Modifier.padding(horizontal = FrpUiTokens.ScreenPadding),
        containerColor = containerColor,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (helpText != null) {
                    SettingsHelpButton { showHelp = true }
                }
                Switch(
                    checked = checked, onCheckedChange = onChange, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
        },
    )
    if (showHelp && helpText != null) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text(title) },
        text = { Text(helpText, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { showHelp = false }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }
    context.startActivity(intent)
}

private fun openBatterySettings(context: Context) {
    if (!isIgnoringBatteryOptimizations(context)) {
        val requestIntent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        runCatching {
            context.startActivity(requestIntent)
        }.onSuccess {
            return
        }
    }
    val detailsIntent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:${context.packageName}"))
    runCatching {
        context.startActivity(detailsIntent)
    }.onFailure {
        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
private fun themeSeedLabel(color: Int): String =
    stringResource(ThemeSeedOptions.firstOrNull { it.color == color }?.labelRes ?: R.string.settings_seed_custom)

@Composable
private fun languageLabel(mode: LanguageMode): String =
    when (mode) {
        LanguageMode.System -> stringResource(R.string.settings_system)
        LanguageMode.Chinese -> stringResource(R.string.settings_chinese)
        LanguageMode.English -> stringResource(R.string.settings_english)
    }

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(when (mode) {
    ThemeMode.System -> R.string.settings_system
    ThemeMode.Light -> R.string.settings_light
    ThemeMode.Dark -> R.string.settings_dark
})
