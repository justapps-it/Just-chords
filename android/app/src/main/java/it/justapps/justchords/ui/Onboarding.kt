package it.justapps.justchords.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import it.justapps.justchords.R
import it.justapps.justchords.core.Dsp
import it.justapps.justchords.core.Learn
import it.justapps.justchords.core.LiveChords
import it.justapps.justchords.core.Music
import it.justapps.justchords.core.PlaylistInfo
import it.justapps.justchords.core.Taste
import it.justapps.justchords.core.TestAnswers
import it.justapps.justchords.core.applyPlaylist
import it.justapps.justchords.data.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** After signing in: a new account answers the first-run questions, others go on. */
fun afterLogin(nav: androidx.navigation.NavController, created: Boolean, onboarded: Long, home: Boolean) {
    if (created || onboarded == 0L) nav.navigate("onboarding") { if (home) popUpTo("home") }
    else if (home) nav.navigate("home") { popUpTo("home") { inclusive = true } }
}

/** Consent (14+, terms, privacy): needed to create or use an account. */
@Composable
fun ConsentBox(consent: Boolean, onChange: (Boolean) -> Unit) {
    val t = rememberT()
    val nav = LocalNav.current
    Row(Modifier.clip(MaterialTheme.shapes.small).clickable { onChange(!consent) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(consent, onChange, colors = CheckboxDefaults.colors(checkedColor = JustOrange))
        Text("${t("consentAge")} ${t("terms")} ${t("consentAnd")} ${t("privacy2")}.", fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
    Row(Modifier.padding(start = 40.dp)) {
        TextButton({ nav.navigate("legal/terms") }) { Text(t("terms"), fontSize = 13.sp) }
        TextButton({ nav.navigate("legal/privacy") }) { Text(t("privacy2"), fontSize = 13.sp) }
    }
}

/** Email + password: sign in, or create an account. Returns (created) through [onDone]. */
@Composable
fun EmailLogin(consent: Boolean, onDone: (Boolean) -> Unit) {
    val t = rememberT()
    val a = app()
    val scope = rememberCoroutineScope()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val ok = email.contains('@') && password.length >= (if (register) 8 else 1) && (!register || consent)
    fun go() {
        busy = true
        err = ""
        scope.launch {
            try {
                onDone(if (register) a.account.register(email, password, name) else a.account.loginWithEmail(email, password))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                err = when (e.status) { 401 -> t("loginFailed"); 409 -> t("emailTaken").also { register = false }; 429 -> t("tooMany"); else -> e.message ?: "" }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (register) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(t("yourName")) }, singleLine = true, shape = MaterialTheme.shapes.medium)
        OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text(t("email")) }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(), label = { Text(t("password")) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (register) ({ Text(t("passwordHint")) }) else null,
        )
        Button(::go, Modifier.fillMaxWidth().height(52.dp), enabled = ok && !busy, colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) {
            Text(if (register) t("createAccount") else t("signIn"), fontWeight = FontWeight.SemiBold)
        }
        if (register && !consent) Muted(t("consentNeeded"), small = true)
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        TextButton({ register = !register; err = "" }) { Text(if (register) t("haveAccount") else t("noAccount")) }
    }
}

/** First launch: sign in (Google or email) or later. */
@Composable
fun WelcomeScreen() {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    fun finish(created: Boolean) {
        a.settings.update { it.copy(welcomed = true) }
        afterLogin(nav, created, a.account.profile.onboarded, home = true)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(88.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.res.colorResource(R.color.icon_background))) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(20.dp))
            Text(t("welcomeTitle"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Muted(t("welcomeText"), Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(28.dp))
            ConsentBox(consent) { consent = it }
            Spacer(Modifier.height(8.dp))
            Button(
                {
                    err = ""
                    scope.launch {
                        try {
                            finish(a.account.completeLogin(googleIdToken(ctx)))
                        } catch (e: GetCredentialCancellationException) {
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            err = e.message ?: e.javaClass.simpleName
                        }
                    }
                },
                Modifier.fillMaxWidth().height(52.dp), enabled = consent,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
            ) { GoogleG(); Spacer(Modifier.width(10.dp)); Text(t("loginGoogle"), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
            if (!email) {
                OutlinedButton({ email = true }, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Rounded.Email, null); Spacer(Modifier.width(10.dp)); Text(t("loginEmail"), fontWeight = FontWeight.SemiBold) }
            } else EmailLogin(consent, ::finish)
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            TextButton({ a.settings.update { it.copy(welcomed = true) }; nav.navigate("home") { popUpTo("home") { inclusive = true } } }) { Text(t("later"), color = just.muted) }
        }
    }
}

/** First-run questions after signing up: level (questions, then the guitar), then a playlist. Everything can be skipped. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen() {
    val t = rememberT()
    val nav = LocalNav.current
    val a = app()
    val st = settings()
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var years by remember { mutableStateOf<Int?>(null) }
    val known = remember { mutableStateListOf<String>() }
    val played = remember { mutableStateListOf<String>() }
    var level by remember { mutableIntStateOf(1) }
    var link by remember { mutableStateOf("") }
    var pl by remember { mutableStateOf<PlaylistInfo?>(null) }
    var plErr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun label(s: String) = Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s
    val steps = 5

    fun finish() {
        var p = a.account.profile
        if (step >= 3 || known.isNotEmpty() || years != null) p = p.copy(level = level)
        pl?.let { p = Taste.applyPlaylist(p, it) }
        a.account.setProfile(p.copy(onboarded = System.currentTimeMillis()))
        nav.navigate("learn") { popUpTo("home") }
    }
    fun next() {
        if (step == 2 || (step == 1 && years == 0 && known.isEmpty())) level = Learn.levelFromTest(TestAnswers(years, known.toSet(), played.toSet()))
        // Never played and no chords: no point in the guitar test.
        step = if (step == 1 && years == 0 && known.isEmpty()) 3 else step + 1
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator({ (step + 1f) / steps }, Modifier.weight(1f), color = JustOrange, trackColor = just.line)
                TextButton(::finish) { Text(t("skipAll"), color = just.muted) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    0 -> {
                        Text(t("testYears"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        listOf("years0", "years1", "years2", "years3").forEachIndexed { i, k ->
                            JustCard(Modifier.fillMaxWidth(), onClick = { years = i; step = 1 }, padding = 16.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t(k), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    if (years == i) Icon(Icons.Rounded.CheckCircle, null, tint = JustOrange)
                                }
                            }
                        }
                    }
                    1 -> {
                        Text(t("testChords"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Muted(t("testChordsHint"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in Learn.TEST_CHORDS) {
                                val on = c in known
                                FilterChip(on, { if (on) known.remove(c) else known.add(c) }, { Text(label(c), fontFamily = PlexMono, fontSize = 16.sp) }, colors = chipColors())
                            }
                        }
                    }
                    2 -> PlayTest(played)
                    3 -> {
                        Text(t("testResult"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(levelName(level), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = JustOrange)
                        Segmented(listOf(1 to levelName(1), 2 to levelName(2), 3 to levelName(3)), level, { level = it }, Modifier.fillMaxWidth())
                        Muted(t("testResultHint"))
                    }
                    else -> {
                        Text(t("playlistTitle"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Muted(t("playlistText"))
                        OutlinedTextField(link, { link = it.trim(); pl = null; plErr = "" }, Modifier.fillMaxWidth(), label = { Text(t("playlistLink")) }, singleLine = true, shape = MaterialTheme.shapes.medium)
                        if (pl == null) OutlinedButton(
                            {
                                busy = true
                                scope.launch {
                                    pl = runCatching { a.account.api.playlist(link) }.onFailure { plErr = t("playlistFailed") }.getOrNull()
                                    busy = false
                                }
                            },
                            enabled = link.startsWith("http") && !busy,
                        ) { Text(if (busy) "…" else "OK") }
                        pl?.let { p ->
                            JustCard(Modifier.fillMaxWidth()) {
                                Text(p.name.ifBlank { p.source }, fontWeight = FontWeight.SemiBold)
                                Muted("${p.artists.size} ${t("playlistFound")}: ${p.artists.take(6).joinToString(", ") { it.first }}", small = true)
                                if (p.genres.isNotEmpty()) Text(
                                    p.genres.mapNotNull { g -> Taste.GENRES.firstOrNull { it.id == g }?.let { if (st.lang == "it") it.it else it.en } }.joinToString(" · "),
                                    color = JustOrange, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                        if (plErr.isNotEmpty()) Text(plErr, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step < 4) {
                    OutlinedButton({ if (step == 0) years = null; next() }, Modifier.weight(1f).height(52.dp)) { Text(t("skip")) }
                    Button(::next, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) { Text(t("continue"), fontWeight = FontWeight.SemiBold) }
                } else {
                    Button(::finish, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = JustOrange)) { Text(t("letsGo"), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** Play the test chords one by one; the microphone says which ones it heard. */
@Composable
private fun PlayTest(played: MutableList<String>) {
    val t = rememberT()
    val st = settings()
    var err by remember { mutableStateOf("") }
    val (mic, start) = rememberMic { err = t("micDenied") }
    var i by remember { mutableIntStateOf(0) }
    var heard by remember { mutableStateOf<String?>(null) }
    val target = Learn.PLAY_TEST.getOrNull(i)
    fun label(s: String) = Music.parseChord(s)?.let { Music.format(it, st.notation) } ?: s
    Text(t("testPlay"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Muted(t("testPlayHint"))
    if (mic != null && target != null) {
        KeepOn()
        DisposableEffect(mic) { onDispose { mic.stop() } }
        LaunchedEffect(mic, target) {
            val dsp = Dsp()
            val live = LiveChords()
            val n = if (mic.sampleRate > 30000) 8192 else 4096
            var streak = 0
            withContext(Dispatchers.Default) {
                while (isActive) {
                    val (chroma, rms) = dsp.chroma(mic.read(n), mic.sampleRate.toFloat())
                    val name = live.push(chroma, rms).chord?.name
                    streak = if (chordMatches(target, name)) streak + 1 else 0
                    withContext(Dispatchers.Main) { heard = name }
                    if (streak >= 4) {
                        withContext(Dispatchers.Main) { played.add(target) }
                        delay(700)
                        withContext(Dispatchers.Main) { i++ }
                        break
                    }
                    delay(100)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Learn.PLAY_TEST.forEach { c ->
            Text(label(c), fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, color = if (c in played) JustOrange else if (c == target) MaterialTheme.colorScheme.onSurface else just.muted)
            if (c in played) Icon(Icons.Rounded.CheckCircle, null, tint = JustOrange, modifier = Modifier.size(16.dp))
        }
    }
    if (target != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ChordCard(target, "${t("playThis")}: ${label(target)}", big = true) }
        if (mic == null) MicButton(start)
        else {
            Text(heard?.let(::label) ?: "…", Modifier.fillMaxWidth(), fontFamily = PlexMono, fontSize = 32.sp, textAlign = TextAlign.Center, color = if (chordMatches(target, heard)) JustOrange else just.muted)
            OutlinedButton({ i++ }, Modifier.fillMaxWidth()) { Text(t("dontKnow")) }
        }
    } else Text("✓ ${played.size}/${Learn.PLAY_TEST.size}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = JustOrange)
    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
}
