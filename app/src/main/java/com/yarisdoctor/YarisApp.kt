package com.yarisdoctor

import android.app.Application
import android.content.Context
import com.yarisdoctor.domain.drive.Sim

/** The process's single [AppContainer], so the service, the activities and the boot receiver share one Db. */
class YarisApp : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as YarisApp).container

/** Owns the databases: the real log, and the in-memory one Debug mode swaps in. */
class AppContainer(private val app: Context) {
    /** The real log, even while Debug mode is on: only this is ever uploaded or exported. */
    val real: Db by lazy { Db(app, "yaris.db") }

    private var debug: Db? = null

    /** What the screens and the service read and write: in Debug mode a fresh in-memory database seeded with fake history; yaris.db is never touched. */
    @Synchronized fun db(): Db {
        if (!Prefs.debug) return real
        return debug ?: Db(app, null).also { debug = it; Sim.seed(it, System.currentTimeMillis()) }
    }

    @Synchronized fun dropDebug() {
        debug?.close()
        debug = null
    }
}
