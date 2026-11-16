package app.pacewise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pacewise.reminders.Reminders
import app.pacewise.sync.PhoneSync
import app.pacewise.ui.PaceWiseTheme
import app.pacewise.ui.PlanScreen
import app.pacewise.ui.ProgressScreen
import app.pacewise.ui.RestoreOnSetup
import app.pacewise.ui.RunDetailScreen
import app.pacewise.ui.RunsScreen
import app.pacewise.ui.SettingsScreen
import app.pacewise.ui.SetupWizard
import app.pacewise.ui.TodayScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Default.Today),
    Tab("plan", "Plan", Icons.Default.CalendarMonth),
    Tab("progress", "Progress", Icons.Default.Insights),
    Tab("runs", "Runs", Icons.AutoMirrored.Filled.DirectionsRun),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as PaceWiseApp).repo
        setContent {
            PaceWiseTheme {
                val profile by repo.profile.collectAsStateWithLifecycle()
                if (profile == null) {
                    SetupWizard(initial = null, onCancel = null, restore = { RestoreOnSetup(repo) }) { p ->
                        repo.saveProfile(p)
                        PhoneSync.pushPlan(this)
                        Reminders.scheduleAll(this)
                    }
                    return@PaceWiseTheme
                }
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                Scaffold(
                    bottomBar = {
                        if (tabs.any { it.route == route }) NavigationBar {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = route == tab.route,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    // Leave room for the bottom bar / system navigation, and tell the screens inside
                    // that this space is already taken so they don't pad for it a second time.
                    val bottom = PaddingValues(bottom = pad.calculateBottomPadding())
                    NavHost(nav, startDestination = "today", modifier = Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                        composable("today") { TodayScreen(repo, onSettings = { nav.navigate("settings") }, onOpenRun = { nav.navigate("run/$it") }, onOpenPlan = { nav.navigate("plan") }) }
                        composable("plan") { PlanScreen(repo, onOpenRun = { nav.navigate("run/$it") }) }
                        composable("progress") { ProgressScreen(repo) }
                        composable("runs") { RunsScreen(repo, onOpenRun = { nav.navigate("run/$it") }) }
                        composable("run/{id}") { e -> RunDetailScreen(repo, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
                        composable("settings") { SettingsScreen(repo, onBack = { nav.popBackStack() }, onEditPlan = { nav.navigate("setup") }) }
                        composable("setup") {
                            SetupWizard(initial = profile, onCancel = { nav.popBackStack() }) { p ->
                                repo.saveProfile(p)
                                PhoneSync.pushPlan(this@MainActivity)
                                nav.popBackStack("today", inclusive = false)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep the watch's copy of the plan fresh (it carries a rolling four-week window).
        PhoneSync.pushPlan(this)
    }
}
