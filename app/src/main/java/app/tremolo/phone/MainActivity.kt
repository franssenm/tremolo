package app.tremolo.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.tremolo.phone.ui.CompareScreen
import app.tremolo.phone.ui.DebugScreen
import app.tremolo.phone.ui.EventLogScreen
import app.tremolo.phone.ui.GuideScreen
import app.tremolo.phone.ui.HandTestsScreen
import app.tremolo.phone.ui.HomeScreen
import app.tremolo.phone.ui.MedicationsScreen
import app.tremolo.phone.ui.NewPlanScreen
import app.tremolo.phone.ui.NewTrialScreen
import app.tremolo.phone.ui.RatingScreen
import app.tremolo.phone.ui.ProgressScreen
import app.tremolo.phone.ui.ReportScreen
import app.tremolo.phone.ui.SettingEditorScreen
import app.tremolo.phone.ui.SettingsScreen
import app.tremolo.phone.ui.SpeechTestScreen
import app.tremolo.phone.ui.SpiralTestScreen
import app.tremolo.phone.ui.TappingTestScreen
import app.tremolo.phone.ui.TimelineScreen
import app.tremolo.phone.ui.TremoloTheme
import app.tremolo.phone.ui.app
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A screen asked for by a notification, widget or shortcut, with a counter so repeats count. */
    private val openRoute = mutableStateOf<Pair<String, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestRoute(intent)
        setContent { TremoloTheme { AppNavigation(openRoute.value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestRoute(intent)
    }

    private fun requestRoute(intent: Intent?) {
        val route = intent?.getStringExtra(Reminders.EXTRA_ROUTE) ?: return
        openRoute.value = route to (openRoute.value?.second ?: 0) + 1
    }

    override fun onResume() {
        super.onResume()
        // Pick up anything the watch delivered while the app was not running.
        val app = application as TremoloApp
        app.appScope.launch { app.ingestor.catchUp() }
    }
}

/** Screens that may be opened directly from outside the app. "home" needs no navigation. */
private val OPENABLE_ROUTES = setOf(
    "rating", "events", "medications", "timeline", "compare", "handTests", "tapping", "spiral",
    "speech", "guide", "newPlan", "newTrial", "settings", "debug",
)

@Composable
private fun AppNavigation(openRoute: Pair<String, Int>?) {
    val nav = rememberNavController()
    val onboarded = app().prefs.onboarded
    val back: () -> Unit = { nav.popBackStack() }

    LaunchedEffect(openRoute) {
        val route = openRoute?.first ?: return@LaunchedEffect
        val allowed = route in OPENABLE_ROUTES || route.startsWith("progress/")
        if (onboarded && allowed) nav.navigate(route) { launchSingleTop = true }
    }

    NavHost(navController = nav, startDestination = if (onboarded) "home" else "onboarding") {
        composable("onboarding") {
            SettingsScreen(firstRun = true, onDone = {
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                nav.navigate("guide")
            })
        }
        composable("home") { HomeScreen(navigate = { nav.navigate(it) }) }
        composable("settings") { SettingsScreen(firstRun = false, onDone = back) }
        composable("newTrial") {
            NewTrialScreen(onBack = back, onEditSetting = { nav.navigate("setting/$it") })
        }
        composable("setting/{id}") { entry ->
            SettingEditorScreen(
                settingId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                onBack = back,
            )
        }
        composable("report/{trialId}") { entry ->
            ReportScreen(trialId = entry.arguments?.getString("trialId").orEmpty(), onBack = back)
        }
        composable("progress/{trialId}") { entry ->
            ProgressScreen(
                trialId = entry.arguments?.getString("trialId").orEmpty(),
                onBack = back,
                navigate = { nav.navigate(it) },
            )
        }
        composable("compare") { CompareScreen(onBack = back) }
        composable("rating") { RatingScreen(onBack = back) }
        composable("events") { EventLogScreen(onBack = back) }
        composable("medications") { MedicationsScreen(onBack = back) }
        composable("timeline") { TimelineScreen(onBack = back) }
        composable("newPlan") {
            NewPlanScreen(onBack = back, onEditSetting = { nav.navigate("setting/$it") })
        }
        composable("handTests") { HandTestsScreen(onBack = back, navigate = { nav.navigate(it) }) }
        composable("tapping") { TappingTestScreen(onBack = back) }
        composable("spiral") { SpiralTestScreen(onBack = back) }
        composable("speech") { SpeechTestScreen(onBack = back) }
        composable("guide") { GuideScreen(onBack = back) }
        composable("debug") { DebugScreen(onBack = back) }
    }
}
