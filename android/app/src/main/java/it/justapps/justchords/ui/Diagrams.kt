package it.justapps.justchords.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.justapps.justchords.core.Chord
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.Voicing
import it.justapps.justchords.core.Voicings

/** Chord box: strings, frets, dots, barre, × and ○ (Diagram.tsx FretDiagram). */
@Composable
fun FretDiagram(v: Voicing, strings: Int, lefty: Boolean = false, scale: Float = 1f) {
    val fg = MaterialTheme.colorScheme.onSurface
    val dot = JustOrange
    val measurer = rememberTextMeasurer()
    val frets = 4
    val w = 16f * (strings - 1)
    val h = 18f * frets
    Canvas(Modifier.size(((w + 28) * scale).dp, ((h + 24) * scale).dp)) {
        val u = size.width / (w + 28)
        val x0 = 14f * u
        val y0 = 16f * u
        fun col(i: Int) = x0 + (if (lefty) strings - 1 - i else i) * 16f * u
        val base = v.base
        if (base == 1) drawRect(fg, Offset(x0, y0 - 3 * u), Size(w * u, 3 * u))
        else {
            val tl = measurer.measure(base.toString(), TextStyle(fontSize = (9 * scale).sp, color = fg.copy(alpha = 0.7f), fontFamily = PlexMono))
            drawText(tl, topLeft = Offset(x0 - 4 * u - tl.size.width, y0 + 12 * u - tl.size.height * 0.8f))
        }
        for (i in 0..frets) drawLine(fg.copy(alpha = 0.35f), Offset(x0, y0 + i * 18 * u), Offset(x0 + w * u, y0 + i * 18 * u), 1f * u)
        for (i in 0 until strings) drawLine(fg.copy(alpha = 0.6f), Offset(x0 + i * 16 * u, y0), Offset(x0 + i * 16 * u, y0 + h * u), 1f * u)
        v.barre?.let { b ->
            val l = minOf(col(b.from), col(b.to)) - 6 * u
            val r = maxOf(col(b.from), col(b.to)) + 6 * u
            drawRoundRect(dot, Offset(l, y0 + (b.fret - base) * 18 * u + 3 * u), Size(r - l, 12 * u), CornerRadius(6 * u))
        }
        v.frets.forEachIndexed { i, f ->
            when {
                f < 0 -> {
                    val tl = measurer.measure("×", TextStyle(fontSize = (11 * scale).sp, color = fg.copy(alpha = 0.7f)))
                    drawText(tl, topLeft = Offset(col(i) - tl.size.width / 2f, y0 - 6 * u - tl.size.height * 0.75f))
                }
                f == 0 -> drawCircle(fg, 4 * u, Offset(col(i), y0 - 9 * u), style = Stroke(1.4f * u))
                else -> drawCircle(dot, 6 * u, Offset(col(i), y0 + (f - base) * 18 * u + 9 * u))
            }
        }
    }
}

/** Three octaves of keys with the chord tones lit (Diagram.tsx PianoDiagram). */
@Composable
fun PianoDiagram(chord: Chord, scale: Float = 1f) {
    val (notes, bass) = remember(chord) { Voicings.pianoNotes(chord) }
    val on = notes.toSet()
    val low = 48
    val blackPc = setOf(1, 3, 6, 8, 10)
    val whites = (low until low + 36).filter { it % 12 !in blackPc }
    val kw = 11f
    val line = MaterialTheme.colorScheme.outline
    val white = Color.White
    val black = Color(0xFF1D1D1F)
    Canvas(Modifier.size((whites.size * kw * scale).dp, (56 * scale).dp)) {
        val u = size.width / (whites.size * kw)
        whites.forEachIndexed { i, m ->
            val fill = if (m in on) JustOrange else if (m == bass) JustOrange.copy(alpha = 0.35f) else white
            drawRect(fill, Offset(i * kw * u, 0f), Size(kw * u, 56 * u))
            drawRect(line, Offset(i * kw * u, 0f), Size(kw * u, 56 * u), style = Stroke(1f))
        }
        for (m in low until low + 36) {
            if (m % 12 !in blackPc) continue
            val x = whites.indexOf(m - 1) * kw + kw * 0.68f
            val fill = if (m in on) JustOrange else if (m == bass) Color(0xFF8A3A1C) else black
            drawRoundRect(fill, Offset(x * u, 0f), Size(kw * 0.64f * u, 34 * u), CornerRadius(1.5f * u))
        }
    }
}

/** A chord with its diagram and the other voicings to flip through (Diagram.tsx ChordCard). */
@Composable
fun ChordCard(symbol: String, display: String, big: Boolean = false, onClick: (() -> Unit)? = null) {
    val st = settings()
    val chord = remember(symbol) { Music.parseChord(symbol) } ?: return
    val tuning = Voicings.tuning(if (st.instrument == "ukulele") "ukulele" else st.tuning)
    val list = remember(symbol, st.instrument, tuning) { if (st.instrument == "piano") emptyList() else Voicings.voicings(chord, tuning, 6) }
    var i by remember(symbol, st.instrument) { mutableIntStateOf(0) }
    JustCard(onClick = onClick, padding = if (big) 20.dp else 10.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(display, fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = if (big) 28.sp else 16.sp, modifier = Modifier.padding(bottom = 4.dp))
            when {
                st.instrument == "piano" -> PianoDiagram(chord, if (big) 1.2f else 0.62f)
                list.isNotEmpty() -> FretDiagram(list[i % list.size], tuning.strings.size, st.lefty, if (big) 2.2f else 1f)
                else -> Box(Modifier.width(80.dp).height(96.dp), contentAlignment = Alignment.Center) { Text("—") }
            }
            if (list.size > 1 && st.instrument != "piano") {
                TextButton({ i = (i + 1) % list.size }, Modifier.height(32.dp)) {
                    Text("${i % list.size + 1}/${list.size} ›", fontFamily = PlexMono, fontSize = 12.sp)
                }
            }
        }
    }
}
