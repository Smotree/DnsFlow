package ru.dnsflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ru.dnsflow.app.recover.RecoverScreen
import ru.dnsflow.app.ui.AppTheme
import ru.dnsflow.app.ui.HistoryScreen
import ru.dnsflow.app.ui.HomeScreen
import ru.dnsflow.app.ui.JoinScreen
import ru.dnsflow.app.ui.ProblemsScreen
import ru.dnsflow.app.ui.ReceiveScreen
import ru.dnsflow.app.ui.ReviewScreen
import ru.dnsflow.app.ui.SessionScreen
import ru.dnsflow.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val nav = rememberNavController()
                val back: () -> Unit = { nav.popBackStack() }
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            openReceive = { id -> nav.navigate("receive/$id") },
                            openReview = { id -> nav.navigate("review/$id") },
                            openJoin = { nav.navigate("join") },
                            openHistory = { nav.navigate("history") },
                            openProblems = { nav.navigate("problems") },
                            openSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("join?from={from}", listOf(navArgument("from") { type = NavType.LongType; defaultValue = -1L })) {
                        val from = it.arguments!!.getLong("from").takeIf { id -> id > 0 }
                        JoinScreen(from, onBack = back, joined = { id -> nav.navigate("receive/$id") { popUpTo("home") } })
                    }
                    composable("receive/{sid}", listOf(navArgument("sid") { type = NavType.LongType })) {
                        val sid = it.arguments!!.getLong("sid")
                        val handle = it.savedStateHandle
                        val recovered by handle.getStateFlow("recovered", -1L).collectAsState()
                        ReceiveScreen(
                            sessionId = sid,
                            recoveredPlaceId = recovered,
                            onRecoveredHandled = { handle["recovered"] = -1L },
                            openDamaged = { nav.navigate("damaged/$sid") },
                            onBack = back,
                            openReview = { nav.navigate("review/$sid") { popUpTo("home") } },
                            openJoin = { id -> nav.navigate("join?from=$id") },
                        )
                    }
                    // part: -1 = this phone's part of a split check (all if not split), 0 = all places.
                    composable(
                        "review/{sid}?placeId={pid}&part={part}",
                        listOf(
                            navArgument("sid") { type = NavType.LongType },
                            navArgument("pid") { type = NavType.LongType; defaultValue = -1L },
                            navArgument("part") { type = NavType.IntType; defaultValue = -1 },
                        ),
                    ) {
                        ReviewScreen(
                            sessionId = it.arguments!!.getLong("sid"),
                            startPlaceId = it.arguments!!.getLong("pid"),
                            part = it.arguments!!.getInt("part"),
                            onBack = back,
                            openProblems = { nav.navigate("problems") { popUpTo("home") } },
                        )
                    }
                    composable("damaged/{sid}", listOf(navArgument("sid") { type = NavType.LongType })) {
                        RecoverScreen(
                            sessionId = it.arguments!!.getLong("sid"),
                            onBack = back,
                            onAdded = { id ->
                                nav.previousBackStackEntry?.savedStateHandle?.set("recovered", id)
                                nav.popBackStack()
                            },
                        )
                    }
                    composable("history") {
                        HistoryScreen(onBack = back, openSession = { id -> nav.navigate("session/$id") })
                    }
                    composable("session/{sid}", listOf(navArgument("sid") { type = NavType.LongType })) {
                        val sid = it.arguments!!.getLong("sid")
                        SessionScreen(
                            sessionId = sid,
                            onBack = back,
                            openReview = { pid, part -> nav.navigate("review/$sid?placeId=$pid&part=$part") },
                            openReceive = { nav.navigate("receive/$sid") },
                            openJoin = { id -> nav.navigate("join?from=$id") },
                        )
                    }
                    composable("problems") {
                        ProblemsScreen(
                            onBack = back,
                            openReview = { sid, pid -> nav.navigate("review/$sid?placeId=$pid&part=0") },
                            reviewAll = { nav.navigate("review/0?part=0") },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = back) }
                }
            }
        }
    }
}
