package it.justapps.justchords.core

// Music theory: chord symbols, transposition, notation (C D E / Do Re Mi),
// key detection, simplification and capo suggestions. Same rules as the web
// app (src/lib/music.ts), so a song looks the same everywhere.

enum class Notation { EN, IT }

data class Chord(val root: Int, val suffix: String, val bass: Int? = null)

data class Key(val tonic: Int, val minor: Boolean)

object Music {
    private val SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    private val IT_SHARP = listOf("Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")
    private val IT_FLAT = listOf("Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si")
    private val LETTER = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11)
    private val SOLFEGE = mapOf("do" to 0, "re" to 2, "mi" to 4, "fa" to 5, "sol" to 7, "la" to 9, "si" to 11)

    fun mod12(n: Int) = ((n % 12) + 12) % 12

    private val ROOT_RE = Regex("^(?:(Do|DO|Re|RE|Mi|MI|Fa|FA|Sol|SOL|La|LA|Si|SI)|([A-H]))([#♯b♭]?)")
    private val SUFFIX_RE =
        Regex("^(?:maj|Maj|MAJ|min|mi|m|M|Δ|-|dim|°|o|aug|\\+|ø|sus|add|no|alt|[#♯b♭]|\\d{1,2}|\\(|\\)|,|/(?=\\d)|\\*)*$")

    private fun parseRoot(s: String): Pair<Int, String>? {
        val m = ROOT_RE.find(s) ?: return null
        var pc = if (m.groupValues[1].isNotEmpty()) SOLFEGE.getValue(m.groupValues[1].lowercase()) else LETTER.getValue(m.groupValues[2][0])
        when (m.groupValues[3]) {
            "#", "♯" -> pc++
            "b", "♭" -> pc--
        }
        return mod12(pc) to s.substring(m.value.length)
    }

    private fun normaliseSuffix(raw: String): String {
        var s = raw.replace("♯", "#").replace("♭", "b").replace(Regex("[()]"), "").replace(Regex("\\*$"), "")
        s = s.replace(Regex("^(min|mi|-)(?!\\d*b5)"), "m").replace(Regex("^-(?=\\d)"), "m")
        s = s.replace(Regex("^Δ7?"), "maj7").replace(Regex("^(Maj|MAJ)"), "maj").replace(Regex("^M(?=\\d|$)"), "maj")
        if (s == "maj") s = ""
        s = s.replace(Regex("^(°|o)7"), "dim7").replace(Regex("^(°|o)$"), "dim").replace(Regex("^ø7?"), "m7b5").replace(Regex("^\\+"), "aug")
        return s
    }

    private val SLASH = Regex("/([^/\\d].*)$")

    fun parseChord(symbol: String): Chord? {
        val sym = symbol.trim()
        if (sym.isEmpty() || sym.length > 16) return null
        val (root, after) = parseRoot(sym) ?: return null
        var rest = after
        var bass: Int? = null
        val slash = SLASH.find(rest)
        if (slash != null) {
            val b = parseRoot(slash.groupValues[1]) ?: return null
            if (b.second.isNotEmpty()) return null
            bass = b.first
            rest = rest.substring(0, slash.range.first)
        }
        if (!SUFFIX_RE.matches(rest)) return null
        return Chord(root, normaliseSuffix(rest), bass)
    }

    fun isChord(s: String) = parseChord(s) != null

    fun noteName(pc: Int, notation: Notation = Notation.EN, flats: Boolean = false): String {
        val table = if (notation == Notation.IT) (if (flats) IT_FLAT else IT_SHARP) else if (flats) FLAT else SHARP
        return table[mod12(pc)]
    }

    fun format(c: Chord, notation: Notation = Notation.EN, flats: Boolean = false): String {
        val bass = c.bass?.let { "/" + noteName(it, notation, flats) } ?: ""
        return noteName(c.root, notation, flats) + c.suffix + bass
    }

    fun transpose(c: Chord, semitones: Int) = Chord(mod12(c.root + semitones), c.suffix, c.bass?.let { mod12(it + semitones) })

    private fun has(s: String, re: String) = Regex(re).containsMatchIn(s)

    /** Intervals (semitones above the root) of the chord, root first. */
    fun intervals(c: Chord): List<Int> {
        val s = c.suffix
        val set = linkedSetOf(0)
        val minor = has(s, "^m(?!aj)")
        val dim = has(s, "^dim") || has(s, "m7b5")
        val aug = has(s, "^aug") || has(s, "#5")
        val sus2 = has(s, "sus2")
        val sus4 = has(s, "sus(4)?(?!2)")
        if (sus2) set.add(2) else if (sus4) set.add(5) else set.add(if (minor || dim) 3 else 4)
        if (s == "5") return listOf(0, 7)
        if (!has(s, "no5")) set.add(if (dim || has(s, "b5")) 6 else if (aug) 8 else 7)
        if (has(s, "no3")) {
            set.remove(3); set.remove(4)
        }
        val ext = Regex("(?:maj|^m|^dim|^aug|^)(\\d{1,2})").find(s.replace(Regex("^mmaj"), "maj"))?.groupValues?.get(1)
        val n = ext?.toInt() ?: 0
        val major7 = has(s, "maj(7|9|11|13)")
        if (has(s, "^dim7")) set.add(9)
        else if (n == 6) set.add(9)
        else if (n >= 7 && n != 69) set.add(if (major7) 11 else 10)
        if (has(s, "6/?9|69")) {
            set.add(9); set.add(2)
        }
        if (n >= 9 && n != 69) set.add(2)
        if (n >= 11) set.add(5)
        if (n >= 13) set.add(9)
        if (has(s, "add9|add2")) set.add(2)
        if (has(s, "add11|add4")) set.add(5)
        if (has(s, "b9")) {
            set.remove(2); set.add(1)
        }
        if (has(s, "#9")) set.add(3)
        if (has(s, "#11")) set.add(6)
        if (has(s, "b13")) set.add(8)
        return set.sorted()
    }

    fun notes(c: Chord) = intervals(c).map { mod12(c.root + it) }

    /** Keeps only the basic triad (or minor/dim): "Cmaj7/E" → "C", "Am9" → "Am". */
    fun simplify(c: Chord): Chord {
        val s = c.suffix
        val suffix = when {
            has(s, "^dim|m7b5") -> "dim"
            has(s, "^m(?!aj)") -> "m"
            has(s, "^aug") -> "aug"
            has(s, "^7|^9|^13|^11") -> "7"
            else -> ""
        }
        return Chord(c.root, suffix)
    }

    // ------------------------------------------------------------ keys

    private val MAJOR_SCALE = listOf(0, 2, 4, 5, 7, 9, 11)
    private val DIATONIC = listOf(0 to 'M', 2 to 'm', 4 to 'm', 5 to 'M', 7 to 'M', 9 to 'm', 11 to 'd')

    private fun kind(c: Chord): Char = when (simplify(c).suffix) {
        "m" -> 'm'
        "dim" -> 'd'
        else -> 'M'
    }

    fun detectKey(chords: List<Chord>): Key? {
        if (chords.isEmpty()) return null
        var best: Pair<Key, Double>? = null
        for (t in 0 until 12) {
            var score = 0.0
            chords.forEachIndexed { i, c ->
                val deg = DIATONIC.find { (iv, k) -> mod12(t + iv) == c.root && k == kind(c) }
                val w = if (i == 0 || i == chords.size - 1) 2 else 1
                if (deg != null) score += w * (if (deg.first == 0 || deg.first == 7 || deg.first == 5) 1.2 else 1.0)
                else if (MAJOR_SCALE.any { mod12(t + it) == c.root }) score += 0.2 * w
                else score -= 0.5 * w
            }
            val first = chords.first()
            val last = chords.last()
            val relMinor = mod12(t + 9)
            fun minorish(c: Chord) = c.root == relMinor && kind(c) == 'm'
            fun tonicish(c: Chord) = c.root == t && kind(c) == 'M'
            val b2i = { b: Boolean -> if (b) 1 else 0 }
            val minor = b2i(minorish(first)) + b2i(minorish(last)) > b2i(tonicish(first)) + b2i(tonicish(last))
            if (tonicish(first)) score += 1
            if (minorish(first)) score += 1
            val key = if (minor) Key(relMinor, true) else Key(t, false)
            if (best == null || score > best.second) best = key to score
        }
        return best!!.first
    }

    fun prefersFlats(key: Key?): Boolean {
        if (key == null) return false
        val major = if (key.minor) mod12(key.tonic + 3) else key.tonic
        return major in listOf(5, 10, 3, 8, 1, 6)
    }

    fun formatKey(key: Key, notation: Notation = Notation.EN) = noteName(key.tonic, notation, prefersFlats(key)) + if (key.minor) "m" else ""

    fun parseKey(s: String): Key? {
        val c = parseChord(
            s.replace(Regex("\\s*(major|maggiore)$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*(minor|minore)$", RegexOption.IGNORE_CASE), "m"),
        ) ?: return null
        return Key(c.root, has(c.suffix, "^m(?!aj)"))
    }

    // ------------------------------------------------------------ capo

    private val EASY = setOf("C", "A", "G", "E", "D", "Am", "Em", "Dm", "A7", "E7", "D7", "G7", "C7", "B7", "Am7", "Em7", "Dm7", "Cmaj7", "Fmaj7", "Amaj7", "Dmaj7", "Gmaj7", "Asus2", "Asus4", "Dsus2", "Dsus4", "Esus4", "Cadd9", "A7sus4", "E5", "A5", "D5", "G5")
    private val MEDIUM = setOf("F", "Bm", "Fm", "Bm7", "F7", "Em9", "Gsus4", "Csus2", "Csus4", "Fsus2", "Bb", "Bdim", "Cm")

    fun shapeDifficulty(c: Chord): Double {
        val name = format(c.copy(bass = null))
        if (name in EASY) return 0.0
        if (name in MEDIUM) return 2.0
        val simple = format(simplify(c))
        if (simple in EASY) return 1.0
        if (simple in MEDIUM) return 2.5
        return 3.5
    }

    data class CapoOption(val capo: Int, val score: Double, val base: Double)

    fun suggestCapo(chords: List<Chord>): List<CapoOption> {
        val unique = LinkedHashMap<String, Chord>().apply { chords.forEach { put(format(it), it) } }.values
        return (0..7).map { capo ->
            val score = unique.sumOf { shapeDifficulty(transpose(it, -capo)) } + capo * 0.15
            CapoOption(capo, score, unique.sumOf { shapeDifficulty(it) })
        }.sortedBy { it.score }
    }
}
