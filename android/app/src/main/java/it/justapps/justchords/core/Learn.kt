package it.justapps.justchords.core

import org.json.JSONObject
import kotlin.math.roundToInt

// "Impara" (src/lib/learn.ts): courses for each level and a practice plan for
// any song. Course content is assets/courses.json, copied from the web app.

data class L10n(val it: String, val en: String) {
    fun of(lang: String) = if (lang == "it") it else en
}

enum class LessonType { CHORD, SWITCH, STRUM, RIFF, PROGRESSION }

data class Lesson(
    val id: String,
    val type: LessonType,
    val title: L10n,
    val tip: L10n? = null,
    val chord: String? = null,
    val chords: List<String> = emptyList(),
    /** Beats per chord (switch, progression). */
    val beats: Int? = null,
    val pattern: String? = null,
    val strum: String? = null,
    val notes: String? = null,
    /** Beats per bar (riffs: 3 or 4). */
    val bar: Int = 4,
    val tempo: Pair<Int, Int>? = null,
)

data class Course(val id: String, val level: Int, val title: L10n, val desc: L10n, val lessons: List<Lesson>)

data class Note(val frets: List<Pair<Int, Int>>, val beats: Double)

/** One thing to play at a beat (after the count-in). */
data class Step(val at: Double, val beats: Double, val chord: String? = null, val stroke: Char? = null, val note: Int? = null)

data class Timeline(val steps: List<Step>, val total: Double, val bar: Int)

data class TestAnswers(val years: Int? = null, val known: Set<String> = emptySet(), val played: Set<String> = emptySet())

data class PlanChord(val shape: String, val from: String?, val hard: Boolean)

data class SongPlan(
    val capo: Int,
    val chords: List<PlanChord>,
    val switches: List<Pair<String, String>>,
    val pattern: String,
    val bpm: Int,
    val tempo: Pair<Int, Int>,
    val progression: List<String>,
    val riffs: List<List<Note>>,
    val lessons: List<Lesson>,
)

data class PlanLabels(val chords: L10n, val switch: L10n, val rhythm: L10n, val riff: L10n, val play: L10n)

object Learn {
    @Volatile var courses: List<Course> = emptyList()
        private set

    fun load(json: String) {
        courses = parse(json)
    }

    private fun l10n(o: JSONObject?) = o?.let { L10n(it.optString("it"), it.optString("en")) }

    fun parse(json: String): List<Course> {
        val a = JSONObject(json).getJSONArray("courses")
        return (0 until a.length()).map { i ->
            val c = a.getJSONObject(i)
            val ls = c.getJSONArray("lessons")
            Course(
                c.getString("id"), c.getInt("level"), l10n(c.getJSONObject("title"))!!, l10n(c.getJSONObject("desc"))!!,
                (0 until ls.length()).map { j ->
                    val l = ls.getJSONObject(j)
                    val type = LessonType.valueOf(l.getString("type").uppercase())
                    val chords = l.optJSONArray("chords")?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
                    val tempo = l.optJSONArray("tempo")?.let { t -> t.getInt(0) to t.getInt(1) }
                    val beats = if (l.has("beats")) l.getInt("beats") else null
                    Lesson(
                        l.getString("id"), type, l10n(l.getJSONObject("title"))!!, l10n(l.optJSONObject("tip")),
                        l.optString("chord").ifEmpty { null }, chords,
                        // In the JSON, a riff's "beats" is the time signature.
                        beats = if (type == LessonType.RIFF) null else beats,
                        pattern = l.optString("pattern").ifEmpty { null }, strum = l.optString("strum").ifEmpty { null },
                        notes = l.optString("notes").ifEmpty { null }, bar = if (type == LessonType.RIFF) beats ?: 4 else 4, tempo = tempo,
                    )
                },
            )
        }
    }

    fun lessonById(id: String): Pair<Course, Lesson>? {
        for (c in courses) c.lessons.firstOrNull { it.id == id }?.let { return c to it }
        return null
    }

    fun coursesFor(level: Int): Pair<List<Course>, List<Course>> =
        courses.filter { it.level == level } to courses.filter { it.level != level }.sortedBy { it.level }

    fun nextLesson(level: Int, done: Set<String>): Pair<Course, Lesson>? {
        val (mine, others) = coursesFor(level)
        for (c in mine + others) c.lessons.firstOrNull { it.id !in done }?.let { return c to it }
        return null
    }

    fun progress(c: Course, done: Set<String>) = c.lessons.count { it.id in done }.toFloat() / c.lessons.size

    fun tempoSteps(t: Pair<Int, Int>?): List<Int> {
        if (t == null) return emptyList()
        val mid = (((t.first + t.second) / 2.0 / 5).roundToInt() * 5)
        return listOf(t.first, mid, t.second).distinct().filter { it > 0 }
    }

    // ------------------------------------------------------------ riffs

    fun parseNotes(src: String): List<Note> = src.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tok ->
        val what = tok.substringBefore(':')
        val beats = tok.substringAfter(':', "").toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        if (what == "r") Note(emptyList(), beats)
        else Note(
            what.split('+').mapNotNull { p ->
                val s = p.substringBefore('.').toIntOrNull()
                val f = p.substringAfter('.', "").toIntOrNull()
                if (s != null && f != null && s in 1..6 && f in 0..24) s to f else null
            },
            beats,
        )
    }

    val STANDARD = intArrayOf(64, 59, 55, 50, 45, 40)
    fun midiOf(s: Int, f: Int) = STANDARD[s - 1] + f

    /** Six tab lines (high E first), one column per note. */
    fun tabLines(notes: List<Note>): Pair<List<String>, List<Int>> {
        val lines = listOf("e", "B", "G", "D", "A", "E").map { StringBuilder("$it|-") }
        val columns = mutableListOf<Int>()
        for (n in notes) {
            val width = maxOf(1, n.frets.maxOfOrNull { it.second.toString().length } ?: 1)
            columns += lines[0].length
            for (s in 1..6) {
                val hit = n.frets.firstOrNull { it.first == s }
                lines[s - 1].append(hit?.second?.toString()?.padEnd(width, '-') ?: "-".repeat(width))
                lines[s - 1].append("-".repeat(maxOf(1, (n.beats * 2).roundToInt())))
            }
        }
        return lines.map { "$it|" } to columns
    }

    /** ASCII tab lines from a song → notes, half a beat per column with a note. */
    fun parseAsciiTab(lines: List<String>): List<Note> {
        val rows = lines.map { it.trim() }.filter { Regex("""^[eEBGDAb]?\s*[|:]?[-0-9hpbr/\\~x|() ]{6,}\|?$""").matches(it) && it.contains('-') }
        if (rows.size < 4) return emptyList()
        val body = rows.take(6).map { it.replace(Regex("""^[a-zA-Z]?\s*[|:]?"""), "") }
        val width = body.maxOf { it.length }
        val notes = mutableListOf<Note>()
        var i = 0
        var gap = 0
        while (i < width) {
            val frets = mutableListOf<Pair<Int, Int>>()
            var used = 1
            body.forEachIndexed { s, b ->
                if (i < b.length) Regex("^\\d{1,2}").find(b.substring(i))?.let { m ->
                    frets += (s + 1) to m.value.toInt()
                    used = maxOf(used, m.value.length)
                }
            }
            if (frets.isNotEmpty()) {
                if (notes.isNotEmpty() && gap > 4) notes[notes.size - 1] = notes.last().copy(beats = 1.0)
                notes += Note(frets, 0.5)
                gap = 0
            } else gap++
            i += used
        }
        return if (notes.size >= 3) notes else emptyList()
    }

    // ------------------------------------------------------- strumming

    fun parsePattern(p: String): List<Char> = p.uppercase().filter { it in "DUX-" }.toList()

    const val BASIC = "D-D-D-D-"
    const val BALLAD = "D-D-D-DU"
    const val POP = "D-DU-UDU"
    const val DRIVE = "DDDDDDDD"

    // -------------------------------------------------------- timeline

    fun timeline(l: Lesson): Timeline {
        val steps = mutableListOf<Step>()
        var at = 0.0
        if (l.type == LessonType.RIFF && l.notes != null) {
            parseNotes(l.notes).forEachIndexed { i, n ->
                steps += Step(at, n.beats, note = i)
                at += n.beats
            }
            return Timeline(steps, kotlin.math.ceil(at / l.bar) * l.bar, l.bar)
        }
        val chords = l.chords.ifEmpty { listOfNotNull(l.chord) }
        if (chords.isEmpty()) return Timeline(steps, 0.0, 4)
        val pattern = parsePattern(if (l.type == LessonType.STRUM) l.pattern ?: BASIC else l.strum ?: "")
        val per = if (l.type == LessonType.STRUM) 8.0 else (l.beats ?: 4).toDouble()
        val seq = when (l.type) {
            LessonType.SWITCH -> List(8) { chords[it % chords.size] }
            LessonType.STRUM -> chords
            else -> chords + chords
        }
        for (chord in seq) {
            steps += Step(at, per, chord = chord)
            if (pattern.isNotEmpty()) {
                val slot = 4.0 / pattern.size
                var b = 0.0
                while (b < per - 1e-9) {
                    val stroke = pattern[(b / slot).roundToInt() % pattern.size]
                    if (stroke != '-') steps += Step(at + b, slot, stroke = stroke)
                    b += slot
                }
            }
            at += per
        }
        return Timeline(steps, at, 4)
    }

    data class Now(val chord: Step?, val next: Step?, val stroke: Step?, val note: Step?)

    fun at(steps: List<Step>, beat: Double): Now {
        var chord: Step? = null
        var next: Step? = null
        var stroke: Step? = null
        var note: Step? = null
        for (s in steps) {
            if (s.chord != null) {
                if (s.at <= beat + 1e-6) chord = s else if (next == null) next = s
            }
            if (s.stroke != null && s.at <= beat + 1e-6 && beat < s.at + s.beats - 1e-6) stroke = s
            if (s.note != null && s.at <= beat + 1e-6) note = s
        }
        return Now(chord, next, stroke, note)
    }

    // ------------------------------------------------------ level test

    val TEST_CHORDS = listOf("Em", "Am", "C", "G", "D", "E", "A", "Dm", "F", "Bm", "B7", "Cmaj7", "F#m", "Bb")
    val PLAY_TEST = listOf("Em", "C", "G", "D", "F", "Bm")

    fun levelFromTest(a: TestAnswers): Int {
        val known = a.known + a.played
        val barre = listOf("F", "Bm", "F#m", "Bb").count { it in known }
        val years = a.years ?: 0
        if ((barre >= 3 && known.size >= 10) || (years >= 3 && barre >= 2)) return 3
        if (barre >= 1 || known.size >= 7 || (years >= 2 && known.size >= 4)) return 2
        return 1
    }

    // ------------------------------------------------------- song plan

    private val EASIER = mapOf(
        "F" to "Fmaj7", "Bm" to "Bm7", "B" to "B7", "Fm" to "Fm7", "F#m" to "F#m7", "Cm" to "Cm7", "C#m" to "C#m7", "Gm" to "Gm7",
        "Bb" to "Bbmaj7", "F#" to "F#7", "Ab" to "Ab7", "Eb" to "Ebmaj7", "G#m" to "G#m7",
    )

    fun simplerShape(c: Chord, level: Int): Chord {
        if (level >= 3) return c
        val plain = if (level == 1) Music.simplify(c) else c.copy(bass = null)
        if (level == 1) EASIER[Music.format(plain)]?.let { alt -> return Music.parseChord(alt) ?: plain }
        return plain
    }

    fun bestCapo(chords: List<Chord>, level: Int): Int {
        val unique = LinkedHashMap<String, Chord>().apply { chords.forEach { put(Music.format(it), it) } }.values
        var best = 0
        var bestScore = Double.MAX_VALUE
        for (capo in 0..7) {
            val score = unique.sumOf { Music.shapeDifficulty(simplerShape(Music.transpose(it, -capo), level)) } + capo * 0.3
            if (score < bestScore - 1e-9) {
                best = capo
                bestScore = score
            }
        }
        return best
    }

    fun songPlan(chordpro: String, level: Int, labels: PlanLabels): SongPlan {
        val song = ChordPro.parse(chordpro)
        val all = ChordPro.songChords(song)
        val sequence = mutableListOf<Chord>()
        val tabBlocks = mutableListOf<List<String>>()
        for (s in song.sections) {
            var tab = mutableListOf<String>()
            for (l in s.lines) {
                if (l is Line.Tab) tab += l.text
                else {
                    if (tab.isNotEmpty()) tabBlocks += tab
                    tab = mutableListOf()
                    if (l is Line.Lyric) l.parts.forEach { p -> p.chord?.let(Music::parseChord)?.let { sequence += it } }
                    if (l is Line.Chords) l.chords.forEach { c -> Music.parseChord(c)?.let { sequence += it } }
                }
            }
            if (tab.isNotEmpty()) tabBlocks += tab
        }
        val capo = if (level < 3 && all.isNotEmpty()) bestCapo(all, level) else 0
        fun shape(c: Chord) = Music.format(simplerShape(Music.transpose(c, -capo), level))
        val src = sequence.ifEmpty { all }
        val seen = LinkedHashMap<String, PlanChord>()
        for (c in src) {
            val sh = shape(c)
            if (sh in seen) continue
            val original = Music.format(Music.transpose(c, -capo))
            seen[sh] = PlanChord(sh, original.takeIf { it != sh }, Music.shapeDifficulty(Music.parseChord(sh)!!) >= (if (level == 1) 1.0 else 2.0))
        }
        val chords = seen.values.toList()
        val shapes = src.map(::shape)
        data class P(val a: String, val b: String, var n: Int, val d: Double)
        val pairs = LinkedHashMap<String, P>()
        for (i in 1 until shapes.size) {
            val a = shapes[i - 1]
            val b = shapes[i]
            if (a == b) continue
            val k = listOf(a, b).sorted().joinToString("|")
            val p = pairs.getOrPut(k) { P(a, b, 0, Music.shapeDifficulty(Music.parseChord(a)!!) + Music.shapeDifficulty(Music.parseChord(b)!!)) }
            p.n++
        }
        val switches = pairs.values.sortedByDescending { it.d * 2 + it.n }.take(if (level == 1) 3 else 4).map { it.a to it.b }
        val bpm = song.tempo?.takeIf { it in 40.0..240.0 }?.roundToInt() ?: 90
        val pattern = when {
            level == 1 -> BASIC
            level == 2 -> POP
            bpm < 80 -> BALLAD
            bpm > 140 -> DRIVE
            else -> POP
        }
        val slow = maxOf(40, ((bpm * (if (level == 1) 0.5 else if (level == 2) 0.6 else 0.7)) / 5).roundToInt() * 5)
        val tempo = minOf(slow, bpm) to bpm
        val progression = mutableListOf<String>()
        for (s in shapes) if (progression.lastOrNull() != s) progression += s
        val riffs = if (level == 1) emptyList() else tabBlocks.map(::parseAsciiTab).filter { it.isNotEmpty() }.take(3)

        val lessons = mutableListOf<Lesson>()
        chords.forEach { c -> lessons += Lesson("chord:${c.shape}", LessonType.CHORD, L10n("${labels.chords.it}: ${c.shape}", "${labels.chords.en}: ${c.shape}"), chord = c.shape) }
        switches.forEach { (a, b) ->
            lessons += Lesson(
                "switch:$a|$b", LessonType.SWITCH, L10n("${labels.switch.it}: $a ↔ $b", "${labels.switch.en}: $a ↔ $b"),
                chords = listOf(a, b), beats = if (level == 1) 4 else 2, tempo = maxOf(40, tempo.first - 10) to bpm,
            )
        }
        lessons += Lesson("rhythm", LessonType.STRUM, labels.rhythm, chords = chords.take(2).map { it.shape }, pattern = pattern, tempo = tempo)
        riffs.forEachIndexed { i, n ->
            val suffix = if (riffs.size > 1) " ${i + 1}" else ""
            lessons += Lesson(
                "riff:$i", LessonType.RIFF, L10n(labels.riff.it + suffix, labels.riff.en + suffix),
                notes = n.joinToString(" ") { x -> (if (x.frets.isEmpty()) "r" else x.frets.joinToString("+") { "${it.first}.${it.second}" }) + ":${fmt(x.beats)}" },
                bar = 4, tempo = tempo,
            )
        }
        if (progression.size > 1) lessons += Lesson("play", LessonType.PROGRESSION, labels.play, chords = progression.take(8), beats = 4, strum = pattern, tempo = tempo)
        return SongPlan(capo, chords, switches, pattern, bpm, tempo, progression, riffs, lessons)
    }

    private fun fmt(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Playback speeds for playing along with the original: slow → original. */
    fun alongSpeeds(level: Int): List<Float> = when (level) {
        1 -> listOf(0.5f, 0.75f, 1f)
        2 -> listOf(0.75f, 0.9f, 1f)
        else -> listOf(0.85f, 1f)
    }
}
