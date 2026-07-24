package it.eldavo.ylih.data

import android.content.Context
import it.eldavo.ylih.tracking.TrackingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency container — the app is far too small to justify a DI framework. */
class AppContainer(context: Context, val clock: Clock = Clock.Wall) {
    private val appContext = context.applicationContext

    /** Outlives any single component: receivers hand work to it via `goAsync`. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: YlihDatabase by lazy { YlihDatabase.open(appContext) }
    val repository: SessionRepository by lazy { SessionRepository(database, clock) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val trackingController: TrackingController by lazy {
        TrackingController(appContext, repository, settings, clock)
    }
}
