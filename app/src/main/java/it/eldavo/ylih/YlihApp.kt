package it.eldavo.ylih

import android.app.Application
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.tracking.Notifications

class YlihApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Almost only wiring: this runs on every process start, including ones a broadcast wakes.
        // Reconciliation happens where it has context — the boot receiver, the activity, the
        // service and the heartbeat worker.
        container = AppContainer(this)
        // The one piece of real work, and it must run this early rather than in the service that
        // needs it. See Notifications.ensureChannelAtStartup: created next to the startForeground
        // that uses it, the channel silently fails to appear when notification permission is
        // denied, and detailed tracking then dies the moment it's switched on. The cost: two
        // binder calls on a broadcast-woken process start.
        Notifications.ensureChannelAtStartup(this)
    }
}
