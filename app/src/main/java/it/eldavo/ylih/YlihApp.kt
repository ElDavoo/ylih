package it.eldavo.ylih

import android.app.Application
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.tracking.Notifications

class YlihApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Deliberately almost only wiring: this runs on every process start, including the ones a
        // broadcast wakes up. Reconciliation happens where it has context — the boot receiver,
        // the activity, the service and the heartbeat worker.
        container = AppContainer(this)
        // The one piece of real work, and it has to be this early rather than in the service that
        // needs it. See Notifications.ensureChannelAtStartup: created next to the startForeground
        // that uses it, the channel silently fails to appear where the notification permission is
        // denied, and detailed tracking then dies the moment it is switched on. Two binder calls
        // on a broadcast-woken process start is what that costs.
        Notifications.ensureChannelAtStartup(this)
    }
}
