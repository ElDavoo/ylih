package it.eldavo.ylih

import android.app.Application
import it.eldavo.ylih.data.AppContainer

class YlihApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Deliberately only wiring, no work: this runs on every process start, including the
        // ones a broadcast wakes up. Reconciliation happens where it has context — the boot
        // receiver, the activity, the service and the heartbeat worker.
        container = AppContainer(this)
    }
}
