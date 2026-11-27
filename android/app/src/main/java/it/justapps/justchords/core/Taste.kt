package it.justapps.justchords.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

// Taste profile, learnt from what you do (src/lib/recs.ts). The same JSON as
// the web app, so it syncs through the account. Ranking runs on the server.

data class Profile(
    val genres: List<String> = emptyList(),
    /** 0 = estimate from the songs I play. */
    val level: Int = 0,
    val artists: Map<String, Double> = emptyMap(),
    val genreBoost: Map<String, Double> = emptyMap(),
    val liked: List<String> = emptyList(),
    val disliked: List<String> = emptyList(),
    /** Learn: lessons done. */
    val done: List<String> = emptyList(),
    /** When the first-run questions were answered or skipped (0 = not yet). */
    val onboarded: Long = 0,
    val updated: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("genres", JSONArray(genres))
        .put("level", level)
        .put("artists", JSONObject(artists as Map<*, *>))
        .put("genreBoost", JSONObject(genreBoost as Map<*, *>))
        .put("liked", JSONArray(liked))
        .put("disliked", JSONArray(disliked))
        .put("done", JSONArray(done))
        .apply { if (onboarded > 0) put("onboarded", onboarded) }
        .put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject?): Profile {
            if (o == null) return Profile()
            fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            fun nums(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.optDouble(it) }.filterValues { !it.isNaN() } } ?: emptyMap()
            return Profile(strs("genres"), o.optInt("level", 0), nums("artists"), nums("genreBoost"), strs("liked"), strs("disliked"), strs("done"), o.optLong("onboarded", 0), o.optLong("updated", 0))
        }
    }
}

data class Genre(val id: String, val it: String, val en: String, val seeds: List<String>)

enum class Signal(val weight: Double) { IMPORT(2.0), OPEN(0.4), FAVORITE(3.0), LIKE(2.0), DISLIKE(-1.2) }

object Taste {
    val GENRES = listOf(
        Genre("pop", "Pop", "Pop", listOf("Ed Sheeran", "Coldplay", "Adele", "Taylor Swift", "Elisa", "Tiziano Ferro")),
        Genre("rock", "Rock", "Rock", listOf("Oasis", "Queen", "Nirvana", "The Beatles", "Foo Fighters", "Radiohead")),
        Genre("classicrock", "Classic rock", "Classic rock", listOf("Led Zeppelin", "Pink Floyd", "The Rolling Stones", "Eagles", "Dire Straits", "AC/DC")),
        Genre("cantautori", "Cantautori", "Italian songwriters", listOf("Lucio Battisti", "Fabrizio De André", "Francesco De Gregori", "Lucio Dalla", "Francesco Guccini", "Rino Gaetano")),
        Genre("itpop", "Pop rock italiano", "Italian pop rock", listOf("Vasco Rossi", "Ligabue", "Negramaro", "Måneskin", "Cesare Cremonini", "Jovanotti")),
        Genre("indie", "Indie", "Indie", listOf("Arctic Monkeys", "The Killers", "The Strokes", "Calcutta", "Phoebe Bridgers", "Tame Impala")),
        Genre("folk", "Folk e acustico", "Folk & acoustic", listOf("Bob Dylan", "Simon & Garfunkel", "Mumford & Sons", "Vance Joy", "Passenger", "Neil Young")),
        Genre("blues", "Blues", "Blues", listOf("Eric Clapton", "B.B. King", "Stevie Ray Vaughan", "John Mayer", "Gary Moore", "The Black Keys")),
        Genre("metal", "Metal", "Metal", listOf("Metallica", "Iron Maiden", "Black Sabbath", "System of a Down", "Avenged Sevenfold", "Pantera")),
        Genre("punk", "Punk", "Punk", listOf("Green Day", "Blink-182", "Ramones", "The Offspring", "Sum 41", "The Clash")),
        Genre("country", "Country", "Country", listOf("Johnny Cash", "Dolly Parton", "Chris Stapleton", "Zach Bryan", "John Denver", "Kacey Musgraves")),
        Genre("soul", "Soul e funk", "Soul & funk", listOf("Bill Withers", "Stevie Wonder", "Marvin Gaye", "Amy Winehouse", "Bruno Mars", "Otis Redding")),
        Genre("reggae", "Reggae", "Reggae", listOf("Bob Marley", "UB40", "Sublime", "Toots and the Maytals", "Alborosie", "Jimmy Cliff")),
        Genre("jazz", "Jazz e bossa nova", "Jazz & bossa nova", listOf("Antônio Carlos Jobim", "João Gilberto", "Norah Jones", "Frank Sinatra", "Chet Baker", "Ella Fitzgerald")),
    )

    fun songKey(title: String, artist: String): String =
        Normalizer.normalize("$artist|$title".lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
            .replace(Regex("[^a-z0-9|]+"), " ")
            .trim()

    fun genresOfArtist(artist: String): List<String> {
        val a = artist.lowercase()
        return GENRES.filter { g -> g.seeds.any { it.lowercase() == a } }.map { it.id }
    }

    fun learn(p: Profile, signal: Signal, title: String, artist: String, genres: List<String>? = null, now: Long = System.currentTimeMillis()): Profile {
        val a = artist.trim().lowercase()
        val artists = p.artists.mapValues { it.value * 0.985 }.filterValues { abs(it) >= 0.05 }.toMutableMap()
        if (a.isNotEmpty()) artists[a] = ((artists[a] ?: 0.0) + signal.weight).coerceIn(-5.0, 25.0)
        val boost = p.genreBoost.toMutableMap()
        for (g in genres ?: genresOfArtist(artist)) boost[g] = ((boost[g] ?: 0.0) + signal.weight * 0.15).coerceIn(-2.0, 3.0)
        val key = songKey(title, artist)
        val liked = if (signal == Signal.LIKE) (p.liked + key).distinct().takeLast(300) else p.liked.filter { signal != Signal.DISLIKE || it != key }
        val disliked = if (signal == Signal.DISLIKE) (p.disliked + key).distinct().takeLast(300) else p.disliked.filter { signal != Signal.LIKE || it != key }
        return p.copy(artists = artists, genreBoost = boost, liked = liked, disliked = disliked, updated = now)
    }
}

data class PlaylistInfo(val name: String, val source: String, val tracks: List<Pair<String, String>>, val artists: List<Pair<String, Int>>, val genres: List<String>)

/** A playlist I like → artists and genres in the taste profile (src/lib/recs.ts applyPlaylist). */
fun Taste.applyPlaylist(p: Profile, pl: PlaylistInfo): Profile {
    val artists = p.artists.toMutableMap()
    for ((name, n) in pl.artists.take(30)) {
        val k = name.trim().lowercase()
        if (k.isNotEmpty()) artists[k] = minOf(25.0, (artists[k] ?: 0.0) + 2 + minOf(n, 5))
    }
    val boost = p.genreBoost.toMutableMap()
    for (g in pl.genres) boost[g] = minOf(3.0, (boost[g] ?: 0.0) + 1)
    return p.copy(artists = artists, genreBoost = boost, genres = (p.genres + pl.genres.take(4)).distinct())
}
