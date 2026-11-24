package it.justapps.justchords.core

// Songs are stored as ChordPro text, like on the web app (src/lib/chordpro.ts):
// parsing for rendering, and pasted text (Ultimate Guitar, Accordi e Spartiti,
// chords-over-lyrics) → ChordPro.

data class Part(val chord: String?, val text: String)

sealed interface Line {
    data class Lyric(val parts: List<Part>) : Line
    data class Chords(val chords: List<String>) : Line
    data class Tab(val text: String) : Line
    data class Comment(val text: String) : Line
    data object Empty : Line
}

enum class SectionKind { VERSE, CHORUS, BRIDGE, TAB, OTHER }

data class Section(val kind: SectionKind, val label: String?, val lines: MutableList<Line> = mutableListOf())

data class ParsedSong(
    var title: String = "",
    var artist: String = "",
    var key: String? = null,
    var capo: Int = 0,
    var tempo: Double? = null,
    var time: String? = null,
    val sections: MutableList<Section> = mutableListOf(),
    val meta: MutableMap<String, String> = mutableMapOf(),
)

data class RenderOptions(val transpose: Int, val notation: Notation, val flats: Boolean, val simplify: Boolean)

object ChordPro {
    private val DIRECTIVE = Regex("^\\{\\s*([a-z_]+)\\s*(?::\\s*(.*?))?\\s*\\}$", RegexOption.IGNORE_CASE)
    private val ALIASES = mapOf(
        "t" to "title", "st" to "subtitle", "a" to "artist", "c" to "comment", "ci" to "comment", "cb" to "comment",
        "soc" to "start_of_chorus", "eoc" to "end_of_chorus", "sov" to "start_of_verse", "eov" to "end_of_verse",
        "sob" to "start_of_bridge", "eob" to "end_of_bridge", "sot" to "start_of_tab", "eot" to "end_of_tab",
        "comment_italic" to "comment", "comment_box" to "comment", "highlight" to "comment",
    )

    fun parse(src: String): ParsedSong {
        val song = ParsedSong()
        var current = Section(SectionKind.OTHER, null)
        fun push() {
            while (current.lines.isNotEmpty() && current.lines.last() == Line.Empty) current.lines.removeAt(current.lines.size - 1)
            if (current.lines.isNotEmpty() || current.label != null) song.sections.add(current)
        }
        fun start(kind: SectionKind, label: String?) {
            push()
            current = Section(kind, label?.ifEmpty { null })
        }
        for (raw in src.replace(Regex("\r\n?"), "\n").split("\n")) {
            val line = raw.trimEnd()
            val d = DIRECTIVE.find(line.trim())
            if (d != null) {
                val n0 = d.groupValues[1].lowercase()
                val name = ALIASES[n0] ?: n0
                val value = d.groups[2]?.value ?: ""
                when {
                    name == "title" -> song.title = value
                    name == "artist" || name == "subtitle" -> if (song.artist.isEmpty()) song.artist = value
                    name == "key" -> song.key = value
                    name == "capo" -> song.capo = Regex("^\\s*-?\\d+").find(value)?.value?.trim()?.toIntOrNull() ?: 0
                    name == "tempo" -> song.tempo = Regex("^\\s*-?\\d+(\\.\\d+)?").find(value)?.value?.trim()?.toDoubleOrNull()?.takeIf { it != 0.0 }
                    name == "time" -> song.time = value
                    name == "comment" -> if (current.lines.none { it != Line.Empty }) start(SectionKind.OTHER, value) else current.lines.add(Line.Comment(value))
                    name.startsWith("start_of_") -> {
                        val k = name.substring(9)
                        val kind = when (k) {
                            "chorus" -> SectionKind.CHORUS
                            "verse" -> SectionKind.VERSE
                            "bridge" -> SectionKind.BRIDGE
                            "tab" -> SectionKind.TAB
                            else -> SectionKind.OTHER
                        }
                        start(kind, value.ifEmpty { null })
                    }
                    name.startsWith("end_of_") -> start(SectionKind.OTHER, null)
                    else -> song.meta[name] = value
                }
                continue
            }
            if (line.startsWith("#")) continue
            if (current.kind == SectionKind.TAB) {
                current.lines.add(if (line.isNotBlank()) Line.Tab(line) else Line.Empty)
                continue
            }
            if (line.isBlank()) {
                if (current.lines.isNotEmpty() && current.lines.last() != Line.Empty) current.lines.add(Line.Empty)
                continue
            }
            current.lines.add(parseLyricLine(line))
        }
        push()
        return song
    }

    private val BRACKET = Regex("\\[([^\\]]*)\\]")

    private fun parseLyricLine(line: String): Line {
        val parts = mutableListOf<Part>()
        var last = 0
        var pending: String? = null
        for (m in BRACKET.findAll(line)) {
            val text = line.substring(last, m.range.first)
            if (text.isNotEmpty() || pending != null) parts.add(Part(pending, text))
            pending = m.groupValues[1].trim()
            last = m.range.last + 1
        }
        val tail = line.substring(last)
        if (tail.isNotEmpty() || pending != null) parts.add(Part(pending, tail))
        if (parts.isNotEmpty() && parts.all { it.text.isBlank() } && parts.any { !it.chord.isNullOrEmpty() }) {
            return Line.Chords(parts.mapNotNull { it.chord?.ifEmpty { null } })
        }
        return Line.Lyric(parts)
    }

    fun songChords(song: ParsedSong): List<Chord> {
        val out = mutableListOf<Chord>()
        for (s in song.sections) for (l in s.lines) {
            val syms = when (l) {
                is Line.Lyric -> l.parts.mapNotNull { it.chord?.ifEmpty { null } }
                is Line.Chords -> l.chords
                else -> emptyList()
            }
            syms.forEach { sym -> Music.parseChord(sym)?.let(out::add) }
        }
        return out
    }

    fun displayChord(sym: String, o: RenderOptions): String {
        val c = Music.parseChord(sym) ?: return sym
        var x = Music.transpose(c, o.transpose)
        if (o.simplify) x = Music.simplify(x)
        return Music.format(x, o.notation, o.flats)
    }

    // ------------------------------------------------------------ import

    private val SECTION_WORDS = Regex(
        "^(intro|verse|strofa|chorus|ritornello|rit|pre[- ]?chorus|pre[- ]?rit(ornello)?|bridge|ponte|outro|finale|coda|solo|interlude|interludio|instrumental|strumentale|hook|refrain|special|tag)\\b\\.?\\s*\\d*\\s*:?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE = Regex(
        "^(chords|accordi|tabs?|tab by|ver(sion)?\\s*\\d|author|autore|difficulty|difficolt[àa]|strumming|last update|rating|\\d+(\\.\\d+)?\\s*/\\s*5|views?|transpose|autoscroll|font|print|stampa|report|save|favorite|preferit|edit|playlist|.*ultimate-guitar\\.com|.*accordiespartiti\\.it|x$)",
        RegexOption.IGNORE_CASE,
    )
    private val TOKEN_SKIP = Regex("^(x\\d+|\\d+x|%|-+|/|n\\.?c\\.?|\\.\\.\\.?|→|>)$", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("^[(\\[]+")
    private val TRAIL = Regex("[)\\],.]+$")
    private val ITALIAN_LOWER = Regex("^(do|re|mi|fa|sol|la|si)")

    fun isChordLine(line: String): Boolean {
        val tokens = line.replace("|", " ")
            .replace(Regex("\\(\\s*x?\\d+\\s*x?\\s*\\)", RegexOption.IGNORE_CASE), " ")
            .split(Regex("\\s+"))
            .map { it.replace(LEAD, "").replace(TRAIL, "") }
            .filter { it.isNotEmpty() && !TOKEN_SKIP.matches(it) }
        if (tokens.isEmpty()) return false
        return tokens.all { Music.isChord(it) && !ITALIAN_LOWER.containsMatchIn(it) }
    }

    data class Hint(val title: String? = null, val artist: String? = null, val url: String? = null)

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun importText(input: String, hint: Hint = Hint()): String {
        var text = input.replace(Regex("\r\n?"), "\n").replace('\u00a0', ' ').replace("\t", "    ")
        val inline = Regex("\\[([^\\]\\s]{1,10})\\][a-zà-ù]", RegexOption.IGNORE_CASE).findAll(text).count { Music.isChord(it.groupValues[1]) }
        if (Regex("^\\s*\\{\\s*(title|t|soc|start_of_\\w+|artist)\\s*:?", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(text) || inline >= 2) {
            return text.trim() + "\n"
        }
        text = text.replace(Regex("\\[/?tab\\]"), "").replace(Regex("\\[ch\\]([^\\[]*?)\\[/ch\\]"), "$1")

        val out = mutableListOf<String>()
        val meta = mutableMapOf<String, String>()
        val lines = text.split("\n")
        var title = hint.title ?: ""
        var artist = hint.artist ?: ""
        var started = false
        var open: String? = null
        fun close() {
            open?.let { out.add("{end_of_$it}") }
            open = null
        }
        val kvRe = Regex("^(capo|key|tonalit[àa]|chiave|tuning|accordatura|tempo|bpm)\\s*[:=]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val titleRe = Regex("^(.+?)\\s+(?:chords|accordi|tab)?\\s*(?:by|di|-|–|—)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val bracketRe = Regex("^\\[([^\\]]+)\\]\\s*(.*)$")
        val tabRe = Regex("^[eBGDAEbgdaH]?\\s*[|:][-0-9hpbrx/\\\\~|. ]{6,}")
        val tabContRe = Regex("^[eBGDAEbgdaH]?\\s*[|:][-0-9hpbrx/\\\\~|. ]{3,}")
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val t = line.trim()
            if (t.isEmpty()) {
                if (out.isNotEmpty() && out.last() != "") out.add("")
                i++; continue
            }
            val kv = kvRe.find(t)
            if (kv != null) {
                val k = kv.groupValues[1].lowercase()
                val v = kv.groupValues[2]
                when {
                    k == "capo" -> meta["capo"] = (Regex("^\\s*\\d+").find(v)?.value?.trim()?.toIntOrNull() ?: 0).toString()
                    k.startsWith("key") || k.startsWith("tonal") || k == "chiave" -> {
                        val first = v.trim().split(Regex("\\s"))[0]
                        if (Music.parseKey(first) != null) meta["key"] = first
                    }
                    k == "tempo" || k == "bpm" -> Regex("^\\s*\\d+(\\.\\d+)?").find(v)?.value?.trim()?.toDoubleOrNull()?.let {
                        meta["tempo"] = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
                    }
                    else -> meta["tuning"] = v
                }
                i++; continue
            }
            if (Regex("^capo\\s+(\\d+)", RegexOption.IGNORE_CASE).containsMatchIn(t) || Regex("^capotasto\\s+(al\\s+)?(\\d+)", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                meta["capo"] = Regex("\\d+").find(t)!!.value
                i++; continue
            }
            if (!started && NOISE.containsMatchIn(t)) {
                i++; continue
            }
            val next = lines.getOrNull(i + 1) ?: ""
            if (!started && !isChordLine(t) && !SECTION_WORDS.containsMatchIn(t.removePrefix("["))) {
                val m = titleRe.find(t)
                if (title.isEmpty() && m != null) {
                    title = m.groupValues[1].trim()
                    if (artist.isEmpty()) artist = m.groupValues[2].trim()
                    i++; continue
                }
                if (title.isEmpty() && t.length < 70 && !isChordLine(next)) {
                    title = t
                    i++; continue
                }
                if (title.isNotEmpty() && artist.isEmpty() && t.length < 50 && !isChordLine(next) && next.isBlank()) {
                    artist = t
                    i++; continue
                }
            }
            val bracket = bracketRe.find(t)
            val sec: Pair<String, String>? = when {
                bracket != null && !Music.isChord(bracket.groupValues[1]) -> bracket.groupValues[1] to bracket.groupValues[2]
                SECTION_WORDS.containsMatchIn(t) -> {
                    val w = SECTION_WORDS.find(t)!!.value
                    w to t.substring(w.length)
                }
                else -> null
            }
            if (sec != null && (sec.second.isBlank() || isChordLine(sec.second))) {
                started = true
                close()
                val label = sec.first.replace(Regex("[:.\\s]+$"), "").trim()
                val kind = when {
                    Regex("chorus|rit", RegexOption.IGNORE_CASE).containsMatchIn(label) && !Regex("pre", RegexOption.IGNORE_CASE).containsMatchIn(label) -> "chorus"
                    Regex("verse|strofa", RegexOption.IGNORE_CASE).containsMatchIn(label) -> "verse"
                    Regex("bridge|ponte", RegexOption.IGNORE_CASE).containsMatchIn(label) -> "bridge"
                    else -> null
                }
                if (kind != null) {
                    out.add("{start_of_$kind: ${cap(label)}}")
                    open = kind
                } else out.add("{comment: ${cap(label)}}")
                if (sec.second.isNotBlank()) out.add(chordsOnly(sec.second))
                i++; continue
            }
            if (tabRe.containsMatchIn(t)) {
                started = true
                val block = mutableListOf(line)
                while (i + 1 < lines.size && tabContRe.containsMatchIn(lines[i + 1].trim())) block.add(lines[++i].trimEnd())
                close()
                out.add("{start_of_tab}"); out.addAll(block); out.add("{end_of_tab}")
                i++; continue
            }
            if (isChordLine(t)) {
                started = true
                val n = next.trimEnd()
                if (n.isNotBlank() && !isChordLine(n) && !SECTION_WORDS.containsMatchIn(n.trim()) && !n.trim().startsWith("[")) {
                    out.add(merge(line, n))
                    i++
                } else out.add(chordsOnly(line))
                i++; continue
            }
            started = true
            out.add(t)
            i++
        }
        close()
        while (out.isNotEmpty() && out.last() == "") out.removeAt(out.size - 1)
        val head = mutableListOf("{title: ${title.ifEmpty { "Senza titolo" }}}")
        if (artist.isNotEmpty()) head.add("{artist: $artist}")
        meta["key"]?.let { head.add("{key: $it}") }
        meta["capo"]?.takeIf { it != "0" }?.let { head.add("{capo: $it}") }
        meta["tempo"]?.let { head.add("{tempo: $it}") }
        meta["tuning"]?.let { head.add("{tuning: $it}") }
        hint.url?.let { head.add("{source: $it}") }
        return head.joinToString("\n") + "\n\n" + out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n") + "\n"
    }

    private data class Tok(val col: Int, val sym: String)

    private fun chordTokens(line: String): List<Tok> = Regex("\\S+").findAll(line).mapNotNull { m ->
        val sym = m.value.replace(LEAD, "").replace(TRAIL, "")
        if (!Music.isChord(sym)) null
        else Tok(m.range.first + (m.value.length - m.value.replace(LEAD, "").length), normaliseImported(sym))
    }.toList()

    private fun normaliseImported(sym: String): String {
        val c = Music.parseChord(sym)!!
        return if (Regex("^(do|re|mi|fa|sol|la|si)", RegexOption.IGNORE_CASE).containsMatchIn(sym)) Music.format(c) else sym
    }

    private fun chordsOnly(line: String) = chordTokens(line).joinToString(" ") { "[${it.sym}]" }

    fun merge(chordLine: String, lyric: String): String {
        val chords = chordTokens(chordLine)
        var text = lyric
        val maxCol = chords.maxOfOrNull { it.col } ?: 0
        if (text.length < maxCol) text = text.padEnd(maxCol, ' ')
        val out = StringBuilder()
        var pos = 0
        for (c in chords) {
            out.append(text, pos, c.col).append("[${c.sym}]")
            pos = c.col
        }
        out.append(text.substring(pos))
        return out.toString().trimEnd().replace(Regex("^ +(?=\\[)"), "")
    }

    data class Meta(val title: String, val artist: String, val key: String?, val capo: Int)

    fun meta(src: String): Meta {
        val p = parse(src)
        return Meta(p.title.ifEmpty { "Senza titolo" }, p.artist, p.key, p.capo)
    }
}
