package it.justapps.justchords

import android.app.Application
import android.content.Context
import it.justapps.justchords.data.Account
import it.justapps.justchords.data.Library
import it.justapps.justchords.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Library, settings and account, shared by every screen. */
class ChordsApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var library: Library
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var account: Account
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("justchords", Context.MODE_PRIVATE)
        library = Library(File(filesDir, "library.json")).apply { load() }
        settings = SettingsStore(prefs)
        account = Account(prefs, library, scope)
        account.init()
    }
}
