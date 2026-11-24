package it.justapps.justchords.core

// Chord diagrams generated from theory (src/lib/voicings.ts): any chord, any
// tuning, guitar or ukulele. Finds playable shapes and ranks them.

enum class Instrument { GUITAR, UKULELE, PIANO }

data class Tuning(val id: String, val name: String, val strings: List<Int>)

data class Barre(val fret: Int, val from: Int, val to: Int)

/** frets: -1 = muted string. */
data class Voicing(val frets: List<Int>, val base: Int, val barre: Barre?, val score: Double)

object Voicings {
    val TUNINGS = listOf(
        Tuning("standard", "E A D G B E", listOf(40, 45, 50, 55, 59, 64)),
        Tuning("dropD", "Drop D", listOf(38, 45, 50, 55, 59, 64)),
        Tuning("halfDown", "Eb Ab Db Gb Bb Eb", listOf(39, 44, 49, 54, 58, 63)),
        Tuning("openG", "Open G", listOf(38, 43, 50, 55, 59, 62)),
        Tuning("openD", "Open D", listOf(38, 45, 50, 54, 57, 62)),
        Tuning("dadgad", "DADGAD", listOf(38, 45, 50, 55, 57, 62)),
    )
    val UKULELE = Tuning("ukulele", "G C E A", listOf(67, 60, 64, 69))

    fun tuning(id: String) = if (id == "ukulele") UKULELE else TUNINGS.find { it.id == id } ?: TUNINGS[0]

    private val cache = HashMap<String, List<Voicing>>()

    fun voicings(c: Chord, tuning: Tuning = TUNINGS[0], limit: Int = 4): List<Voicing> {
        val key = "${c.root}|${c.suffix}|${c.bass}|${tuning.strings}"
        synchronized(cache) { cache[key] }?.let { return it.take(limit) }

        val intervals = Music.intervals(c)
        val pcs = intervals.map { (c.root + it) % 12 }.toMutableSet()
        val bass = c.bass ?: c.root
        if (c.bass != null) pcs.add(c.bass)
        val required = intervals.filter { it != 7 }.map { (c.root + it) % 12 }.toMutableSet()
        required.add(bass)
        val strings = tuning.strings
        val n = strings.size
        val isUke = n == 4
        val out = mutableListOf<Voicing>()
        val seen = HashSet<String>()

        fun consider(fr: IntArray) {
            val played = fr.mapIndexed { i, f -> if (f < 0) -1 else strings[i] + f }
            val first = played.indexOfFirst { it >= 0 }
            if (first < 0) return
            for (i in first until n) if (played[i] < 0) return
            val sounding = played.filter { it >= 0 }
            if (sounding.size < (if (isUke) 4 else minOf(4, pcs.size + 1))) return
            if (!isUke && sounding.min() % 12 != bass) return
            val have = sounding.map { it % 12 }.toSet()
            if (!have.containsAll(required)) return
            val fretted = fr.filter { it > 0 }
            val minF = fretted.minOrNull() ?: 0
            val maxF = fretted.maxOrNull() ?: 0
            if (maxF - minF > 3) return
            val atMin = fretted.count { it == minF }
            val isBarre = minF > 0 && atMin >= 2 && fretted.size > 4 - (if (isUke) 1 else 0)
            val fingers = if (isBarre) fretted.size - atMin + 1 else fretted.size
            if (fingers > 4) return
            var barre: Barre? = null
            if (isBarre) {
                val from = fr.indexOfFirst { it == minF }
                for (i in from until n) if (fr[i] in 0 until minF) return
                barre = Barre(minF, from, n - 1)
            }
            val k = fr.joinToString(",")
            if (!seen.add(k)) return
            val opens = fr.count { it == 0 }
            val score = fingers * 1.0 + (maxF - minF) * 0.6 + minF * 0.35 + (if (isBarre) 1.2 else 0.0) - opens * 0.3 - sounding.size * 0.4 +
                (if (fr[0] < 0 && !isUke) 0.1 else 0.0)
            out.add(Voicing(fr.toList(), if (maxF <= 4) 1 else minF, barre, score))
        }

        for (base in 0..12) {
            val options = strings.map { open ->
                val o = mutableListOf<Int>()
                if (!isUke) o.add(-1)
                if (open % 12 in pcs) o.add(0)
                for (f in maxOf(1, base)..base + 3) if ((open + f) % 12 in pcs) o.add(f)
                o
            }
            val frets = IntArray(n)
            fun walk(s: Int) {
                if (s == n) return consider(frets.copyOf())
                for (f in options[s]) {
                    frets[s] = f
                    walk(s + 1)
                }
            }
            walk(0)
        }
        val sorted = out.sortedBy { it.score }
        synchronized(cache) { cache[key] = sorted }
        return sorted.take(limit)
    }

    /** Piano: chord tones as MIDI notes around middle C (bass an octave lower). */
    fun pianoNotes(c: Chord): Pair<List<Int>, Int> {
        val root = 60 + c.root
        return Music.intervals(c).map { root + if (it > 12) it - 12 else it } to 48 + (c.bass ?: c.root)
    }
}
