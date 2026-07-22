package com.orka.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.orka.core.designsystem.OrkaTheme
import java.time.Instant
import com.orka.core.model.ArchiveRoute
import com.orka.core.model.CaptureRoute
import com.orka.core.model.DiagnosticsRoute
import com.orka.core.model.OnboardingRoute
import com.orka.core.model.ModelInstaller
import com.orka.core.model.SettingsRepository
import com.orka.core.model.SettingsRoute
import com.orka.core.model.TaskDetailRoute
import com.orka.core.model.TaskRepository
import com.orka.core.model.TasksRoute
import com.orka.core.model.UserSettings
import com.orka.feature.alarm.AlarmRoute
import com.orka.feature.archive.ArchiveRoute as ArchiveScreen
import com.orka.feature.capture.CaptureRoute as CaptureScreen
import com.orka.feature.diagnostics.DiagnosticsRoute as DiagnosticsScreen
import com.orka.feature.onboarding.OnboardingRoute as OnboardingScreen
import com.orka.feature.settings.SettingsRoute as SettingsScreen
import com.orka.feature.taskdetail.TaskDetailRoute as TaskDetailScreen
import com.orka.feature.tasks.TasksRoute as TasksScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // hiltViewModel() inside OrkaApp() resolves to this same instance (same ViewModelStoreOwner,
    // the Activity) — obtained here too just to gate the splash screen.
    private val rootViewModel: RootViewModel by viewModels()

    // The full-screen alarm intent is the primary way a fired reminder is shown, but it can be
    // refused by background-activity-start restrictions (see OrkaAlarmReceiver) — the tappable
    // notification body is the fallback path, and launches MainActivity via this action with the
    // reminder id attached. Without tracking it, tapping that notification just opened the app to
    // whatever its normal start tab is, with no way to tell which task the notification was about.
    private val pendingReminderId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // DataStore's first read is genuinely async. Without this, the very first composition of
        // OrkaApp() would see RootUiState()'s default (onboardingCompleted = false) and NavHost
        // would build its graph with OnboardingRoute as the start destination — then, once the
        // real (true) value streams in a moment later, the graph gets rebuilt with a different
        // start destination, which resets NavController's back stack to it. Net effect for a
        // returning user on every cold start: a visible flash of the onboarding screen before it
        // snaps to Capture. Holding the splash screen open until the real value is in avoids ever
        // building the wrong graph in the first place.
        splashScreen.setKeepOnScreenCondition { !rootViewModel.uiState.value.isSettingsLoaded }
        enableEdgeToEdge()
        pendingReminderId.value = intent.getStringExtra("reminder_id")
        setContent {
            OrkaApp(pendingReminderId = pendingReminderId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingReminderId.value = intent.getStringExtra("reminder_id")
    }
}

@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    // android:launchMode="singleTop" means a second alarm firing while this one is still on
    // screen reuses this same instance via onNewIntent rather than creating a new Activity —
    // without tracking that in observable state, the UI would keep showing the first alarm's
    // task even after the user taps the second alarm's notification.
    private val reminderIdState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        reminderIdState.value = intent.getStringExtra("reminder_id").orEmpty()
        setContent {
            OrkaTheme {
                val reminderId by reminderIdState
                AlarmRoute(
                    reminderId = reminderId,
                    onComplete = {
                        // OrkaAlarmReceiver posts this as ongoing/non-auto-cancel (it must survive
                        // until the user acts on it, like a real alarm) — so it has to be
                        // explicitly dismissed here once that action is taken, using the same
                        // notification id (reminderId.hashCode()) the receiver notified with.
                        if (reminderId.isNotEmpty()) {
                            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                                ?.cancel(reminderId.hashCode())
                        }
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderIdState.value = intent.getStringExtra("reminder_id").orEmpty()
    }
}

private data class TopLevelDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

data class RootUiState(
    val settings: UserSettings = UserSettings(),
    val isSettingsLoaded: Boolean = false,
)

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    modelInstaller: ModelInstaller,
    private val taskRepository: TaskRepository,
) : ViewModel() {

    init {
        viewModelScope.launch {
            modelInstaller.installBundledModelIfAvailable()
            // Loads the model into memory now, in the background, rather than paying that same
            // cost (which real-device testing showed can run into minutes for a model this size)
            // the first time the user actually taps Analyse. By the time they've described a task
            // and tapped the button, this has had a head start — best case it's already warm and
            // that tap only pays real inference latency.
            modelInstaller.warmUp()
        }
        viewModelScope.launch {
            // AlarmRefreshWorker also does this every 6h, but that leaves a stale window right
            // after each app open/reinstall — cheap enough to just re-check on every launch too.
            taskRepository.markOverdueTasks(Instant.now())
        }
    }

    val uiState = settingsRepository.observeSettings()
        .map { settings -> RootUiState(settings = settings, isSettingsLoaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootUiState())

    suspend fun resolveTaskIdForReminder(reminderId: String): String? =
        taskRepository.getReminder(reminderId)?.taskId
}

@Composable
fun OrkaApp(
    pendingReminderId: androidx.compose.runtime.MutableState<String?>? = null,
    viewModel: RootViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onboardingCompleted = uiState.settings.onboardingCompleted
    val startRoute = if (onboardingCompleted) CaptureRoute else OnboardingRoute
    val darkTheme = uiState.settings.darkModeOverride ?: isSystemInDarkTheme()

    val reminderId = pendingReminderId?.value
    LaunchedEffect(reminderId, onboardingCompleted) {
        if (reminderId != null && onboardingCompleted) {
            val taskId = viewModel.resolveTaskIdForReminder(reminderId)
            if (taskId != null) {
                navController.navigate(TaskDetailRoute(taskId))
            }
            // Clear regardless of whether the reminder resolved, so a stale/consumed id doesn't
            // re-trigger navigation on the next unrelated recomposition (e.g. a theme change).
            pendingReminderId.value = null
        }
    }

    // Theme.Orka hardcodes windowLightStatusBar/windowLightNavigationBar to false (light bar
    // icons) identically in both values/ and values-night/ — it can't express ORKA's own
    // Light/Dark override, which is independent of the system theme. Without this, picking
    // "Light" in Settings would leave status/nav bar icons white-on-white and unreadable.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    OrkaTheme(darkTheme = darkTheme) {
        Scaffold(
            bottomBar = {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val destination = backStackEntry?.destination
                if (!destination.isOnboarding()) {
                    OrkaBottomBar(navController = navController, destination = destination)
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = startRoute,
                modifier = Modifier.padding(padding),
            ) {
                composable<OnboardingRoute> {
                    OnboardingScreen(
                        onGrantExactAlarmPermission = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            } else {
                                appDetailsIntent(navController.context)
                            }
                            navController.context.startActivity(intent)
                        },
                        onOpenBatterySettings = {
                            // Requests exemption for this app directly (one-tap system dialog)
                            // instead of the generic "all apps" list, which requires the user
                            // to find ORKA themselves. Falls back if an OEM ROM doesn't honor it.
                            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${navController.context.packageName}"))
                            safeStartActivity(navController.context, direct) {
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            }
                        },
                        onOpenOemSettings = {
                            openDeviceSpecificOemSettings(navController.context)
                        },
                        onOpenFullScreenIntentSettings = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                    .setData(Uri.parse("package:${navController.context.packageName}"))
                            } else {
                                appDetailsIntent(navController.context)
                            }
                            safeStartActivity(navController.context, intent) { appDetailsIntent(navController.context) }
                        },
                        onOpenNotificationSettings = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, navController.context.packageName)
                            safeStartActivity(navController.context, intent) { appDetailsIntent(navController.context) }
                        },
                        onFinish = {
                            navController.navigate(CaptureRoute) {
                                popUpTo(OnboardingRoute) { inclusive = true }
                            }
                        },
                    )
                }
                composable<CaptureRoute> {
                    CaptureScreen(
                        onTaskCreated = { taskId -> navController.navigate(TaskDetailRoute(taskId)) },
                    )
                }
                composable<TasksRoute> {
                    TasksScreen(onTaskClick = { taskId -> navController.navigate(TaskDetailRoute(taskId)) })
                }
                composable<TaskDetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<TaskDetailRoute>()
                    TaskDetailScreen(taskId = route.taskId, onBack = { navController.popBackStack() })
                }
                composable<ArchiveRoute> {
                    ArchiveScreen()
                }
                composable<SettingsRoute> {
                    SettingsScreen()
                }
                composable<DiagnosticsRoute> {
                    DiagnosticsScreen()
                }
            }
        }
    }
}

@Composable
private fun OrkaBottomBar(
    navController: NavHostController,
    destination: NavDestination?,
) {
    val items = listOf(
        TopLevelDestination(CaptureRoute, "Capture", Icons.Outlined.Edit),
        TopLevelDestination(TasksRoute, "Tasks", Icons.AutoMirrored.Outlined.ListAlt),
        TopLevelDestination(ArchiveRoute, "Archive", Icons.Outlined.Archive),
        TopLevelDestination(SettingsRoute, "Settings", Icons.Outlined.Settings),
        TopLevelDestination(DiagnosticsRoute, "Diagnostics", Icons.Outlined.BugReport),
    )

    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                selected = destination?.hasRoute(item.route::class) == true,
                onClick = {
                    // Without popUpTo/saveState/restoreState, bouncing between tabs pushes a new
                    // back stack entry (and keeps its ViewModel/composable state alive) every
                    // single tap — the back stack grows without bound for the whole session, and
                    // the system back button replays tab-visit history instead of behaving like a
                    // normal bottom-nav app. This is the standard pattern Google's own bottom-nav
                    // guidance recommends specifically to avoid that.
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
            )
        }
    }
}

private fun NavDestination?.isOnboarding(): Boolean = this?.hasRoute(OnboardingRoute::class) == true

private fun appDetailsIntent(context: Context): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    .setData(Uri.fromParts("package", context.packageName, null))

/** Launches [intent], falling back to [fallback] if the device has no activity that resolves it. */
private fun safeStartActivity(context: Context, intent: Intent, fallback: () -> Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(fallback())
    }
}

private fun openDeviceSpecificOemSettings(context: Context) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val intents = buildList {
        if (manufacturer.contains("oneplus") || manufacturer.contains("oplus")) {
            add(Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")))
            add(Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.permission.PermissionActivity")))
            add(Intent("com.oneplus.security.action.BACKGROUND_OPTIMIZE"))
        }
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(appDetailsIntent(context))
    }

    val launchableIntent = intents.firstOrNull { it.resolveActivity(context.packageManager) != null }
        ?: appDetailsIntent(context)

    try {
        context.startActivity(launchableIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(appDetailsIntent(context))
    }
}
