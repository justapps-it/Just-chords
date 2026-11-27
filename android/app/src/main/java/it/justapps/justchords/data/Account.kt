package it.justapps.justchords.data

import android.content.SharedPreferences
import it.justapps.justchords.core.Levels
import it.justapps.justchords.core.Notation
import it.justapps.justchords.core.Profile
import it.justapps.justchords.core.Signal
import it.justapps.justchords.core.Taste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

// Settings, taste profile and Google account (src/settings.ts, src/account.ts).

data class Settings(
    val lang: String = if (Locale.getDefault().language == "it") "it" else "en",
    val notation: Notation = if (Locale.getDefault().language == "it") Notation.IT else Notation.EN,
    val instrument: String = "guitar",
    val tuning: String = "standard",
    val theme: String = "auto",
    val a4: Int = 440,
    val lefty: Boolean = false,
    /** The welcome screen (sign in or later) was shown. */
    val welcomed: Boolean = false,
)

class SettingsStore(private val prefs: SharedPreferences) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            prefs.getString("lang", d.lang) ?: d.lang,
            runCatching { Notation.valueOf(prefs.getString("notation", d.notation.name)!!) }.getOrDefault(d.notation),
            prefs.getString("instrument", d.instrument) ?: d.instrument,
            prefs.getString("tuning", d.tuning) ?: d.tuning,
            prefs.getString("theme", d.theme) ?: d.theme,
            prefs.getInt("a4", d.a4),
            prefs.getBoolean("lefty", d.lefty),
            prefs.getBoolean("welcomed", d.welcomed),
        )
    }

    fun update(f: (Settings) -> Settings) {
        val s = f(_state.value)
        _state.value = s
        prefs.edit().putString("lang", s.lang).putString("notation", s.notation.name).putString("instrument", s.instrument)
            .putString("tuning", s.tuning).putString("theme", s.theme).putInt("a4", s.a4).putBoolean("lefty", s.lefty).putBoolean("welcomed", s.welcomed).apply()
    }
}

data class AccountState(
    val user: User? = null,
    val profile: Profile = Profile(),
    val sync: String = "idle",
    val error: String? = null,
)

class Account(
    private val prefs: SharedPreferences,
    private val library: Library,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val api = Api(token = { token })
    private var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply()

    private val _state = MutableStateFlow(
        AccountState(
            user = if (token != null) runCatching { User.fromJson(JSONObject(prefs.getString("user", "")!!)) }.getOrNull() else null,
            profile = runCatching { Profile.fromJson(JSONObject(prefs.getString("profile", "")!!)) }.getOrDefault(Profile()),
        ),
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()
    val profile get() = _state.value.profile

    private fun writeProfile(p: Profile) = prefs.edit().putString("profile", p.toJson().toString()).apply()

    private var profileJob: Job? = null
    fun setProfile(next: Profile) {
        val p = next.copy(updated = now())
        writeProfile(p)
        _state.update { it.copy(profile = p) }
        if (_state.value.user != null) {
            profileJob?.cancel()
            profileJob = scope.launch {
                delay(1500)
                runCatching { api.putProfile(_state.value.profile) }.onFailure(::onAuthError)
            }
        }
    }

    fun signal(kind: Signal, title: String, artist: String, genres: List<String>? = null) {
        if (title.isBlank() && artist.isBlank()) return
        setProfile(Taste.learn(profile, kind, title, artist, genres ?: Taste.genresOfArtist(artist), now()))
    }

    /** The level to recommend for: chosen, or estimated from the library. */
    fun effectiveLevel(songs: List<Song>): Int {
        if (profile.level != 0) return profile.level
        return Levels.estimate(songs.map { SongLevels.of(it.chordpro) to (1.0 + (if (it.favorite) 2 else 0) + (if (it.opened != null) 1 else 0)) })
    }

    private fun onAuthError(e: Throwable) {
        if (e is ApiError && e.status == 401) logout()
    }

    /** Google sign-in. Returns true for a brand-new account (first-run questions follow). */
    suspend fun completeLogin(credential: String): Boolean = finishLogin(api.loginWithGoogle(credential))

    suspend fun loginWithEmail(email: String, password: String): Boolean = finishLogin(api.loginWithEmail(email.trim(), password))

    suspend fun register(email: String, password: String, name: String): Boolean = finishLogin(api.register(email.trim(), password, name.trim()))

    private suspend fun finishLogin(r: Api.Login): Boolean {
        token = r.token
        prefs.edit().putString("user", r.user.toJson().toString()).apply()
        // The newer profile wins; a brand-new account takes the local one.
        val local = profile
        val p = if (r.profile != null && r.profile.updated > local.updated) r.profile else local
        writeProfile(p)
        _state.update { it.copy(user = r.user, profile = p) }
        if (p !== r.profile) runCatching { api.putProfile(p) }
        syncNow()
        return r.created
    }

    /** Learn: a lesson is done. */
    fun markDone(id: String) {
        if (id in profile.done) return
        setProfile(profile.copy(done = (profile.done + id).takeLast(1000)))
    }

    fun setOnboarded() = setProfile(profile.copy(onboarded = now()))

    fun logout() {
        token = null
        prefs.edit().remove("user").apply()
        _state.update { it.copy(user = null, sync = "idle", error = null) }
    }

    /** Deletes the account and everything stored for it on the server. The copy on this phone stays. */
    suspend fun deleteAccount() {
        api.deleteAccount()
        logout()
    }

    /** Everything about me, as JSON (data portability). */
    fun exportData(): String = JSONObject()
        .put("exported", Instant.ofEpochMilli(now()).toString())
        .put("user", _state.value.user?.toJson() ?: JSONObject.NULL)
        .put("profile", profile.toJson())
        .put("library", library.export())
        .toString(2)

    private val syncLock = Mutex()
    suspend fun syncNow() {
        if (_state.value.user == null) return
        syncLock.withLock {
            _state.update { it.copy(sync = "syncing") }
            try {
                library.import(api.sync(library.export()))
                _state.update { it.copy(sync = "idle", error = null) }
            } catch (e: Exception) {
                onAuthError(e)
                _state.update { it.copy(sync = "error", error = e.message) }
            }
        }
    }

    private var libJob: Job? = null
    fun init() {
        library.onChange = {
            if (_state.value.user != null) {
                libJob?.cancel()
                libJob = scope.launch {
                    delay(4000)
                    syncNow()
                }
            }
        }
        if (token == null) return
        scope.launch {
            try {
                val (user, p) = api.me()
                prefs.edit().putString("user", user.toJson().toString()).apply()
                if (p != null && p.updated > profile.updated) {
                    writeProfile(p)
                    _state.update { it.copy(user = user, profile = p) }
                } else _state.update { it.copy(user = user) }
                syncNow()
            } catch (e: Exception) {
                onAuthError(e)
            }
        }
    }
}
