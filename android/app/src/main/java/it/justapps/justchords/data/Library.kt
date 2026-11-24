package it.justapps.justchords.data

import it.justapps.justchords.core.ChordPro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

// Songs and setlists on the phone, in the same JSON as the web app
// (src/store.ts), so the account sync moves them between web and app as is.

data class Song(
    val id: String,
    val chordpro: String,
    val title: String,
    val artist: String,
    val created: Long,
    val updated: Long,
    val opened: Long? = null,
    val transpose: Int = 0,
    val capo: Int? = null,
    val scroll: Int = 3,
    val font: Double = 1.0,
    val favorite: Boolean = false,
    val source: String? = null,
    val youtube: String? = null,
    /** Fields this version does not know, kept for the other devices. */
    val extra: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject(extra.toString())
        o.put("id", id).put("chordpro", chordpro).put("title", title).put("artist", artist)
            .put("created", created).put("updated", updated).put("transpose", transpose).put("scroll", scroll).put("font", font)
        opened?.let { o.put("opened", it) } ?: o.remove("opened")
        capo?.let { o.put("capo", it) } ?: o.remove("capo")
        if (favorite) o.put("favorite", true) else o.remove("favorite")
        source?.let { o.put("source", it) } ?: o.remove("source")
        youtube?.let { o.put("youtube", it) } ?: o.remove("youtube")
        return o
    }

    companion object {
        private val KNOWN = setOf("id", "chordpro", "title", "artist", "created", "updated", "opened", "transpose", "capo", "scroll", "font", "favorite", "source", "youtube")

        fun fromJson(o: JSONObject): Song? {
            val id = o.optString("id").ifEmpty { return null }
            val extra = JSONObject()
            for (k in o.keys()) if (k !in KNOWN) extra.put(k, o.get(k))
            fun long(k: String) = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).ifEmpty { null } else null
            return Song(
                id, o.optString("chordpro"), o.optString("title"), o.optString("artist"),
                o.optLong("created", 0), o.optLong("updated", 0), long("opened"),
                o.optInt("transpose", 0), long("capo")?.toInt(), o.optInt("scroll", 3), o.optDouble("font", 1.0).takeIf { !it.isNaN() } ?: 1.0,
                o.optBoolean("favorite", false), str("source"), str("youtube"), extra,
            )
        }
    }
}

data class Setlist(val id: String, val name: String, val songs: List<String>, val updated: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("songs", JSONArray(songs)).put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject): Setlist? {
            val id = o.optString("id").ifEmpty { return null }
            val a = o.optJSONArray("songs") ?: JSONArray()
            return Setlist(id, o.optString("name"), (0 until a.length()).map { a.optString(it) }, o.optLong("updated", 0))
        }
    }
}

data class LibraryState(
    val songs: List<Song> = emptyList(),
    val setlists: List<Setlist> = emptyList(),
    val deleted: Map<String, Long> = emptyMap(),
    val loaded: Boolean = false,
) {
    fun song(id: String) = songs.find { it.id == id }

    fun toJson(): JSONObject = JSONObject()
        .put("songs", JSONArray(songs.map { it.toJson() }))
        .put("setlists", JSONArray(setlists.map { it.toJson() }))
        .put("deleted", JSONObject(deleted as Map<*, *>))

    companion object {
        fun fromJson(o: JSONObject): LibraryState {
            val s = o.optJSONArray("songs") ?: JSONArray()
            val l = o.optJSONArray("setlists") ?: JSONArray()
            val d = o.optJSONObject("deleted") ?: JSONObject()
            return LibraryState(
                (0 until s.length()).mapNotNull { s.optJSONObject(it)?.let(Song::fromJson) },
                (0 until l.length()).mapNotNull { l.optJSONObject(it)?.let(Setlist::fromJson) },
                d.keys().asSequence().associateWith { d.optLong(it) },
                true,
            )
        }
    }
}

fun uid(): String {
    val a = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..8).map { a[Random.nextInt(a.length)] }.joinToString("") + System.currentTimeMillis().toString(36).takeLast(4)
}

/** The library, saved as one JSON file (a few hundred songs are a few MB at most). */
class Library(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Called after a change worth syncing (not on view settings). */
    var onChange: () -> Unit = {}

    fun load() {
        _state.value = runCatching { LibraryState.fromJson(JSONObject(file.readText())) }.getOrElse { LibraryState(loaded = true) }
    }

    private fun commit(next: LibraryState, changed: Boolean) {
        _state.value = next.copy(loaded = true)
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(next.toJson().toString())
            tmp.renameTo(file)
        }
        if (changed) onChange()
    }

    @Synchronized
    fun saveSong(chordpro: String, id: String? = null, patch: (Song) -> Song = { it }): Song {
        val meta = ChordPro.meta(chordpro)
        val s = _state.value
        val old = id?.let(s::song)
        val t = now()
        val base = old ?: Song(id ?: uid(), chordpro, "", "", t, t)
        val song = patch(base.copy(chordpro = chordpro, title = meta.title, artist = meta.artist, updated = t))
        commit(s.copy(songs = s.songs.filter { it.id != song.id } + song), true)
        return song
    }

    /** View settings change often: no "updated" bump, no sync. With [sync] the song is bumped and synced. */
    @Synchronized
    fun patchSong(id: String, sync: Boolean = false, patch: (Song) -> Song) {
        val s = _state.value
        val old = s.song(id) ?: return
        val song = patch(old).let { if (sync) it.copy(updated = now()) else it }
        commit(s.copy(songs = s.songs.map { if (it.id == id) song else it }), sync)
    }

    @Synchronized
    fun deleteSong(id: String) {
        val s = _state.value
        val t = now()
        commit(
            s.copy(
                songs = s.songs.filter { it.id != id },
                setlists = s.setlists.map { if (id in it.songs) it.copy(songs = it.songs - id, updated = t) else it },
                deleted = s.deleted + (id to t),
            ),
            true,
        )
    }

    @Synchronized
    fun saveSetlists(next: List<Setlist>) {
        val s = _state.value
        val t = now()
        val gone = s.setlists.filter { o -> next.none { it.id == o.id } }.associate { "sl:${it.id}" to t }
        commit(s.copy(setlists = next, deleted = s.deleted + gone), true)
    }

    fun findBySource(url: String) = _state.value.songs.find { it.source == url }

    fun export(): JSONObject = _state.value.toJson()

    /** Replaces the library with the merged copy from the account, keeping local view settings of unchanged songs. */
    @Synchronized
    fun import(lib: JSONObject) {
        val incoming = LibraryState.fromJson(lib)
        val mine = _state.value.songs.associateBy { it.id }
        val songs = incoming.songs.map { x -> mine[x.id]?.takeIf { it.updated == x.updated } ?: x }
        commit(incoming.copy(songs = songs), false)
    }
}
