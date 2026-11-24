package it.justapps.justchords.core

import kotlin.math.roundToInt

// How hard a song is to play on guitar (src/lib/difficulty.ts).

data class Difficulty(val score: Int, val level: Int, val capo: Int, val hard: List<String>)

object Levels {
    fun chords(chords: List<Chord>, changesPerLine: Int = 2): Difficulty {
        val unique = LinkedHashMap<String, Chord>().apply { chords.forEach { put(Music.format(it), it) } }.values.toList()
        if (unique.isEmpty()) return Difficulty(0, 1, 0, emptyList())
        val capo = Music.suggestCapo(unique).firstOrNull()?.capo ?: 0
        val shapes = unique.map { Music.transpose(it, -capo) }
        val d = shapes.map { Music.shapeDifficulty(it) }
        val hard = shapes.filterIndexed { i, _ -> d[i] >= 2 }.map { Music.format(it) }
        val worst = d.max()
        val mean = d.average()
        val score = minOf(
            100.0,
            worst * 9 + mean * 12 + maxOf(0, unique.size - 4) * 3.5 + hard.size * 4 + maxOf(0, changesPerLine - 2) * 6 + (if (capo != 0) 2 else 0),
        ).roundToInt()
        return Difficulty(score, if (score < 25) 1 else if (score < 55) 2 else 3, capo, hard)
    }

    fun changesPerLine(song: ParsedSong): Int {
        val counts = song.sections.flatMap { s ->
            s.lines.map {
                when (it) {
                    is Line.Lyric -> it.parts.count { p -> !p.chord.isNullOrEmpty() }
                    is Line.Chords -> it.chords.size
                    else -> 0
                }
            }
        }.filter { it > 0 }.sorted()
        return if (counts.isEmpty()) 2 else counts[counts.size / 2]
    }

    fun song(chordpro: String): Difficulty {
        val s = ChordPro.parse(chordpro)
        return chords(ChordPro.songChords(s), changesPerLine(s))
    }

    /** The level a player is at, from the songs they play: (level, weight). */
    fun estimate(played: List<Pair<Int, Double>>): Int {
        val total = played.sumOf { it.second }
        if (total < 3) return 1
        var acc = 0.0
        for (p in played.sortedByDescending { it.first }) {
            acc += p.second
            if (acc >= total * 0.3) return p.first
        }
        return 1
    }
}
