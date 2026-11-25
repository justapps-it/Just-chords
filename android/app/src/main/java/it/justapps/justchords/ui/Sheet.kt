package it.justapps.justchords.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.justapps.justchords.core.ChordPro
import it.justapps.justchords.core.Line
import it.justapps.justchords.core.ParsedSong
import it.justapps.justchords.core.RenderOptions
import it.justapps.justchords.core.SectionKind

private data class Piece(val chord: String?, val text: String, val join: Boolean)

/** Word by word: each word carries the chord above it, so lines wrap anywhere and chords stay on their syllable. */
private fun groups(parts: List<it.justapps.justchords.core.Part>): List<List<Piece>> {
    val units = mutableListOf<Piece>()
    for (p in parts) {
        val words = Regex("\\S+\\s*|\\s+").findAll(p.text).map { it.value }.toList().ifEmpty { listOf("") }
        words.forEachIndexed { i, w ->
            units += Piece(if (i == 0) p.chord else null, w, i == 0 && units.isNotEmpty() && !units.last().text.endsWith(" "))
        }
    }
    val out = mutableListOf<MutableList<Piece>>()
    for (u in units) if (u.join && out.isNotEmpty()) out.last() += u else out += mutableListOf(u)
    return out
}

@Composable
private fun ChordLabel(sym: String, opts: RenderOptions, scale: Float, onChord: ((String) -> Unit)?) {
    Text(
        ChordPro.displayChord(sym, opts),
        Modifier.clip(RoundedCornerShape(6.dp)).then(if (onChord != null) Modifier.clickable { onChord(sym) } else Modifier).padding(end = 6.dp),
        color = JustOrange,
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = (15 * scale).sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetLine(line: Line, opts: RenderOptions, scale: Float = 1f, onChord: ((String) -> Unit)? = null) {
    val lyric = (17 * scale).sp
    when (line) {
        is Line.Empty -> Spacer(Modifier.height((10 * scale).dp))
        is Line.Comment -> Text(line.text, Modifier.padding(vertical = 4.dp), color = just.muted, fontStyle = FontStyle.Italic, fontSize = (15 * scale).sp)
        is Line.Tab -> Text(
            line.text,
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
            fontFamily = PlexMono, fontSize = (13 * scale).sp, softWrap = false,
        )
        is Line.Chords -> FlowRow(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            line.chords.forEach { ChordLabel(it, opts, scale * 1.05f, onChord) }
        }
        is Line.Lyric -> {
            if (line.parts.none { !it.chord.isNullOrEmpty() }) {
                Text(line.parts.joinToString("") { it.text }, fontSize = lyric, lineHeight = lyric * 1.35f)
                return
            }
            FlowRow(Modifier.padding(vertical = 2.dp)) {
                for (g in groups(line.parts)) {
                    Row {
                        for (u in g) {
                            Column {
                                if (u.chord != null) ChordLabel(u.chord, opts, scale, onChord) else Text(" ", fontFamily = PlexMono, fontSize = (15 * scale).sp)
                                Text(u.text.ifEmpty { " " }, fontSize = lyric, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The song body as LazyColumn items: sections with their labels; the chorus gets the orange rule of the site. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.sheetItems(song: ParsedSong, opts: RenderOptions, scale: Float, onChord: ((String) -> Unit)?) {
    song.sections.forEachIndexed { si, s ->
        if (!s.label.isNullOrEmpty()) item(key = "h$si") {
            Text(
                s.label.uppercase(),
                Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                color = if (s.kind == SectionKind.CHORUS) JustOrange else just.muted,
                fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.8.sp,
            )
        }
        s.lines.forEachIndexed { li, l ->
            item(key = "l$si-$li") {
                val chorus = s.kind == SectionKind.CHORUS
                val bar = JustOrange.copy(alpha = 0.5f)
                Row(
                    Modifier.padding(horizontal = 20.dp).then(
                        // The chorus gets a continuous orange rule on the left, like the website.
                        if (chorus) Modifier.drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }.padding(start = 15.dp) else Modifier,
                    ),
                ) {
                    SheetLine(l, opts, scale, onChord)
                }
            }
        }
    }
}

/** Read-only sheet for previews (import, editor). */
@Composable
fun SheetPreview(src: String) {
    val st = settings()
    val parsed = ChordPro.parse(src)
    val key = parsed.key?.let(it.justapps.justchords.core.Music::parseKey) ?: it.justapps.justchords.core.Music.detectKey(ChordPro.songChords(parsed))
    val opts = RenderOptions(0, st.notation, it.justapps.justchords.core.Music.prefersFlats(key), false)
    Column(Modifier.fillMaxWidth()) {
        Text(parsed.title, style = MaterialTheme.typography.titleLarge)
        if (parsed.artist.isNotEmpty()) Text(parsed.artist, color = just.muted)
        Spacer(Modifier.height(8.dp))
        for (s in parsed.sections) {
            if (!s.label.isNullOrEmpty()) Eyebrow(s.label, Modifier.padding(top = 12.dp, bottom = 2.dp), if (s.kind == SectionKind.CHORUS) JustOrange else just.muted)
            for (l in s.lines) SheetLine(l, opts)
        }
    }
}
