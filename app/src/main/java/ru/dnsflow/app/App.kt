package ru.dnsflow.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.dnsflow.app.data.AppDao
import ru.dnsflow.app.data.AppDb
import ru.dnsflow.app.data.Prefs
import ru.dnsflow.app.lookup.DnsLookup

class App : Application() {
    lateinit var db: AppDb
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var lookup: DnsLookup
        private set

    val dao: AppDao get() = db.dao()

    /** For writes that must finish even if the screen or dialog that started them closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb.open(this)
        prefs = Prefs(this)
        lookup = DnsLookup(this)
        lookup.start()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
