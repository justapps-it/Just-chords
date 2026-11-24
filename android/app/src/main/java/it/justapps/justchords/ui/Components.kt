package it.justapps.justchords.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import it.justapps.justchords.ChordsApp
import it.justapps.justchords.data.Settings

val LocalNav = staticCompositionLocalOf<NavHostController> { error("no nav") }

@Composable
fun app(): ChordsApp = LocalContext.current.applicationContext as ChordsApp

@Composable
fun settings(): Settings = app().settings.state.collectAsState().value

/** The web app's words, in the language chosen in the app (like the site's IT/EN switch). */
@Composable
fun rememberT(): (String) -> String {
    val lang = settings().lang
    return remember(lang) { val m = if (lang == "it") I18n.it else I18n.en; { k: String -> m[k] ?: k } }
}

@Composable
fun LevelBadge(level: Int, modifier: Modifier = Modifier) {
    val t = rememberT()
    val c = when (level) {
        1 -> just.level1
        2 -> just.level2
        else -> just.level3
    }
    Row(
        modifier.clip(CircleShape).background(c.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            for (i in 1..3) Box(Modifier.width(3.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= level) c else c.copy(alpha = 0.25f)))
        }
        Spacer(Modifier.width(6.dp))
        Text(t("level$level"), color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The big rounded card of justapps.it. */
@Composable
fun JustCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val inner: @Composable () -> Unit = { Column(Modifier.padding(padding), content = content) }
    if (onClick != null) {
        Surface(onClick, modifier.border(1.dp, just.line, shape), shape = shape, color = just.card, content = inner)
    } else {
        Surface(modifier.border(1.dp, just.line, shape), shape = shape, color = just.card, content = inner)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(text, modifier, color = just.muted, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = just.muted) {
    Text(text.uppercase(), modifier, color = color, fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp)
}

/** A page with a large collapsing title and a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = { },
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val nav = LocalNav.current
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = floating,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton({ if (nav.previousBackStackEntry != null) nav.popBackStack() else nav.navigate("home") }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                        }
                    }
                },
                actions = actions,
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
                scrollBehavior = behavior,
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = v == selected,
                onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = { Text(label, maxLines = 1, fontSize = 13.sp) },
            )
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))
