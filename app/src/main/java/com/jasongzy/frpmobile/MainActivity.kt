package com.jasongzy.frpmobile

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.app.ActivityManager
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jasongzy.frpmobile.core.data.AppGraph
import com.jasongzy.frpmobile.core.data.FrpSettings
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.runtime.FrpForegroundService
import com.jasongzy.frpmobile.ui.theme.FrpMobileTheme
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.ui.dashboard.DashboardScreen
import com.jasongzy.frpmobile.ui.editor.EditorScreen
import com.jasongzy.frpmobile.ui.logs.LogsScreen
import com.jasongzy.frpmobile.ui.profiles.ProfilesScreen
import com.jasongzy.frpmobile.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class AppLaunchState : ViewModel() {
    var handled = false
}

class MainActivity : AppCompatActivity() {
    private val startDestination = mutableStateOf<String?>(null)
    private val launchState by viewModels<AppLaunchState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startDestination.value = if (savedInstanceState == null) intent?.getStringExtra(FrpForegroundService.EXTRA_START_DESTINATION) else null
        val repository = AppGraph.repository(this)
        FrpForegroundService.restoreDesired(this)
        if (!launchState.handled) {
            launchState.handled = true
            startOnLaunch(intent)
        }
        lifecycleScope.launch {
            repository.settings.map { it.excludeFromRecents }.distinctUntilChanged().collect { exclude ->
                @Suppress("DEPRECATION")
                getSystemService(ActivityManager::class.java).appTasks
                    .firstOrNull { it.taskInfo?.id == taskId }?.setExcludeFromRecents(exclude)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (repository.settings.first().rootMode) repository.syncRuntimeStates()
            }
        }
        setContent {
            val settingsFlow = remember(repository) {
                repository.settings.map<FrpSettings, FrpSettings?> { it }
            }
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { currentSettings ->
                FrpMobileTheme(
                    seedColor = currentSettings.themeSeedColor,
                    mode = currentSettings.themeMode,
                    languageMode = currentSettings.languageMode,
                ) {
                    ApplySystemBars()
                    FrpApp(startDestination = startDestination.value, onDestinationConsumed = { startDestination.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startDestination.value = intent.getStringExtra(FrpForegroundService.EXTRA_START_DESTINATION)
        startOnLaunch(intent)
    }

    private fun startOnLaunch(intent: Intent?) {
        if (intent?.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        lifecycleScope.launch {
            try {
                val result = FrpForegroundService.startOnAppLaunch(this@MainActivity).await()
                if (!result.isSuccess && result.code != "NO_TARGETS") {
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
private fun ApplySystemBars() {
    val view = LocalView.current
    val background = MaterialTheme.colorScheme.background
    val backgroundArgb = background.toArgb()
    val darkMode = background.luminance() < 0.5f
    SideEffect {
        if (!view.isInEditMode) {
            view.context.findActivity()?.let { activity ->
                activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(backgroundArgb, backgroundArgb) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(backgroundArgb, backgroundArgb) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    activity.window.isNavigationBarContrastEnforced = false
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrpApp(startDestination: String?, onDestinationConsumed: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = Screen.topLevel.any { it.route == currentRoute }
    val isEditor = currentRoute?.startsWith("editor/") == true
    val tabRoutes = remember { Screen.topLevel.map { it.route } }
    val editorOffset = with(LocalDensity.current) { 48.dp.roundToPx() } *
        if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val topBarTitleRes = when (currentRoute) {
        Screen.Dashboard.route -> R.string.nav_dashboard
        Screen.Profiles.route -> R.string.nav_profiles
        Screen.Logs.route -> R.string.nav_logs
        Screen.Settings.route -> R.string.nav_settings
        else -> if (isEditor) R.string.editor_screen_title else R.string.app_title
    }
    LaunchedEffect(startDestination) {
        if (startDestination == FrpForegroundService.DESTINATION_LOGS) {
            navController.navigateToTab(Screen.Logs.route)
            onDestinationConsumed()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Crossfade(topBarTitleRes, animationSpec = tween(180), label = "pageTitle") { titleRes ->
                        Text(stringResource(titleRes))
                    }
                },
                navigationIcon = {
                    AnimatedVisibility(
                        visible = isEditor,
                        enter = expandHorizontally(tween(EDITOR_TRANSITION_MS, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                        exit = shrinkHorizontally(tween(EDITOR_TRANSITION_MS, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
                    ) {
                        FrpIconButton(
                            label = stringResource(R.string.navigate_back),
                            enabled = isEditor,
                            onClick = { activity?.onBackPressedDispatcher?.onBackPressed() },
                            tooltipBelow = true,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            Box(Modifier.navigationBarsPadding()) {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = expandVertically(tween(EDITOR_TRANSITION_MS, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(EDITOR_TRANSITION_MS, easing = FastOutSlowInEasing)) + fadeOut(tween(90)),
                ) {
                    BottomNavigation(navController)
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds(),
            enterTransition = {
                if (initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes) {
                    slideIntoContainer(
                        if (tabRoutes.indexOf(targetState.destination.route) > tabRoutes.indexOf(initialState.destination.route)) {
                            AnimatedContentTransitionScope.SlideDirection.Left
                        } else AnimatedContentTransitionScope.SlideDirection.Right,
                        tween(280, easing = FastOutSlowInEasing),
                    )
                } else if (initialState.destination.route?.startsWith("editor/") == true ||
                    targetState.destination.route?.startsWith("editor/") == true) {
                    val offset = if (targetState.destination.route?.startsWith("editor/") == true) editorOffset else -editorOffset
                    slideInHorizontally(tween(EDITOR_TRANSITION_MS, easing = FastOutSlowInEasing)) { offset } +
                        fadeIn(tween(180, delayMillis = 70))
                } else EnterTransition.None
            },
            exitTransition = {
                if (initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes) {
                    slideOutOfContainer(
                        if (tabRoutes.indexOf(targetState.destination.route) > tabRoutes.indexOf(initialState.destination.route)) {
                            AnimatedContentTransitionScope.SlideDirection.Left
                        } else AnimatedContentTransitionScope.SlideDirection.Right,
                        tween(280, easing = FastOutSlowInEasing),
                    )
                } else if (initialState.destination.route?.startsWith("editor/") == true ||
                    targetState.destination.route?.startsWith("editor/") == true) {
                    val offset = if (targetState.destination.route?.startsWith("editor/") == true) -editorOffset else editorOffset
                    slideOutHorizontally(tween(EDITOR_TRANSITION_MS, easing = FastOutSlowInEasing)) { offset } +
                        fadeOut(tween(90))
                } else ExitTransition.None
            },
        ) {
            frpGraph(navController)
        }
    }
}

private const val EDITOR_TRANSITION_MS = 300
private const val FOCUS_BOOT_START = "focusBootStart"
private const val FOCUS_PROFILE = "focusProfile"

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.id) { saveState = true }
        restoreState = true
        launchSingleTop = true
    }
}

private fun NavGraphBuilder.frpGraph(navController: NavHostController) {
    composable(Screen.Dashboard.route) {
        DashboardScreen(
            onOpenBootStartSettings = {
                navController.navigateToTab(Screen.Settings.route)
                navController.getBackStackEntry(Screen.Settings.route).savedStateHandle[FOCUS_BOOT_START] = true
            },
            onOpenProfile = { id ->
                navController.navigateToTab(Screen.Profiles.route)
                navController.getBackStackEntry(Screen.Profiles.route).savedStateHandle[FOCUS_PROFILE] = id
            },
        )
    }
    composable(Screen.Profiles.route) { entry ->
        val focusProfileId by entry.savedStateHandle.getStateFlow<String?>(FOCUS_PROFILE, null).collectAsStateWithLifecycle()
        ProfilesScreen(
            focusProfileId = focusProfileId,
            onProfileFocusHandled = { id ->
                if (entry.savedStateHandle.get<String>(FOCUS_PROFILE) == id) {
                    entry.savedStateHandle[FOCUS_PROFILE] = null
                }
            },
            onEditProfile = { id -> navController.navigate("editor/$id") { launchSingleTop = true } },
            onNewProfile = { type -> navController.navigate("editor/new/${type.name}") { launchSingleTop = true } },
        )
    }
    composable(Screen.Logs.route) {
        LogsScreen()
    }
    composable(Screen.Settings.route) { entry ->
        val focusBootStart by entry.savedStateHandle.getStateFlow(FOCUS_BOOT_START, false).collectAsStateWithLifecycle()
        SettingsScreen(
            focusBootStart = focusBootStart,
            onBootStartFocusHandled = { entry.savedStateHandle[FOCUS_BOOT_START] = false },
        )
    }
    composable("editor/{profileId}") { backStackEntry ->
        EditorScreen(
            profileId = backStackEntry.arguments?.getString("profileId").orEmpty(),
            onExit = { if (navController.currentBackStackEntry == backStackEntry) navController.popBackStack() },
        )
    }
    composable("editor/new/{type}") { backStackEntry ->
        EditorScreen(
            newType = FrpType.valueOf(requireNotNull(backStackEntry.arguments?.getString("type"))),
            onExit = { if (navController.currentBackStackEntry == backStackEntry) navController.popBackStack() },
        )
    }
}

@Composable
private fun BottomNavigation(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val enabled = Screen.topLevel.any { it.route == currentDestination?.route }
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0),
    ) {
        Screen.topLevel.forEach { screen ->
            NavigationBarItem(
                enabled = enabled,
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    if (currentDestination?.route != screen.route) {
                        navController.navigateToTab(screen.route)
                    }
                },
                icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                label = { Text(stringResource(screen.labelRes)) },
            )
        }
    }
}

private sealed class Screen(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    data object Dashboard : Screen("dashboard", R.string.nav_dashboard, Icons.Rounded.Dashboard)
    data object Profiles : Screen("profiles", R.string.nav_profiles, Icons.Rounded.Tune)
    data object Logs : Screen("logs", R.string.nav_logs, Icons.AutoMirrored.Rounded.Article)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Rounded.Settings)

    companion object {
        val topLevel = listOf(Dashboard, Profiles, Logs, Settings)
    }
}
