package it.justapps.justchords.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.justapps.justchords.core.LinkKind
import it.justapps.justchords.core.Sources

fun enc(s: String): String = Uri.encode(s)

/** Where a shared text or link should go: a song link → find its chords, else → import. */
fun shareRoute(subject: String?, text: String?): String {
    val link = Sources.firstLink(text ?: "")
    val kind = link?.let(Sources::linkKind)
    // TikTok and Instagram videos: listen to them and work out the chords.
    if (kind == LinkKind.TIKTOK || kind == LinkKind.INSTAGRAM) return "analyze?url=${enc(link)}"
    if (link != null && kind != null) return "find?url=${enc(link)}"
    return "import?title=${enc(subject ?: "")}&text=${enc(text ?: "")}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector)

private val TAB_ROUTES = setOf("home", "find", "tools", "profile")

@Composable
fun JustChordsRoot(startRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val t = rememberT()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')?.substringBefore('/')
    val tabs = listOf(
        Tab("home", t("library"), Icons.Outlined.Home, Icons.Rounded.Home),
        Tab("find", if (settings().lang == "it") "Cerca" else "Search", Icons.Outlined.Search, Icons.Rounded.Search),
        Tab("tools", t("tools"), Icons.Outlined.Tune, Icons.Rounded.Tune),
        Tab("profile", t("profile"), Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
    )
    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            nav.navigate(startRoute)
            onRouteConsumed()
        }
    }
    CompositionLocalProvider(LocalNav provides nav) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                Box(Modifier.weight(1f).imePadding()) { Routes(nav) }
                AnimatedVisibility(route in TAB_ROUTES, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, windowInsets = WindowInsets(0)) {
                        for (tab in tabs) {
                            val on = route == tab.route
                            NavigationBarItem(
                                selected = on,
                                onClick = {
                                    if (!on) nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (on) tab.selected else tab.icon, null) },
                                label = { Text(tab.label, fontSize = 12.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = just.orangeSoft, selectedIconColor = JustOrange, selectedTextColor = JustOrange),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun NavBackStackEntry.arg(name: String) = arguments?.getString(name)?.takeIf { it.isNotEmpty() }

@Composable
private fun Routes(nav: NavHostController) {
    val tab = TAB_ROUTES
    NavHost(
        nav, "home",
        enterTransition = {
            if (targetState.destination.route?.substringBefore('?') in tab) fadeIn() else slideInHorizontally { it / 4 } + fadeIn()
        },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        composable("home") { HomeScreen() }
        composable("find?q={q}&url={url}&t={t}&a={a}&yt={yt}") { e ->
            FindScreen(e.arg("q"), e.arg("url"), e.arg("t"), e.arg("a"), e.arg("yt"))
        }
        composable("tools") { ToolsScreen() }
        composable("profile") { ProfileScreen() }
        composable("song/{id}") { e -> SongRoute(e.arg("id")!!, stage = false) }
        composable("stage/song/{id}", enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) { e -> SongRoute(e.arg("id")!!, stage = true) }
        composable("stage/{list}/{i}", enterTransition = { fadeIn() }, exitTransition = { ExitTransition.None }) { e ->
            StageSetlistRoute(e.arg("list")!!, e.arg("i")?.toIntOrNull() ?: 0)
        }
        composable("edit/{id}") { e -> EditorScreen(e.arg("id")!!) }
        composable("import?title={title}&text={text}&audio={audio}") { e -> ImportScreen(e.arg("title"), e.arg("text"), e.arg("audio") == "1") }
        composable("setlist/{id}") { e -> SetlistScreen(e.arg("id")!!) }
        composable("tuner") { TunerScreen() }
        composable("metronome") { MetronomeScreen() }
        composable("listen") { ListenScreen() }
        composable("chords") { DictionaryScreen() }
        composable("analyze?url={url}&file={file}") { e -> AnalyzeScreen(e.arg("url"), e.arg("file")) }
        composable("legal/{doc}") { e -> LegalScreen(e.arg("doc") ?: "privacy") }
    }
}
