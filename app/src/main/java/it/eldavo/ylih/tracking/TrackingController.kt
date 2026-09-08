package it.eldavo.ylih.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import it.eldavo.ylih.Distribution
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import it.eldavo.ylih.data.Clock
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.SessionRepository
import it.eldavo.ylih.data.SettingsStore
import it.eldavo.ylih.data.trackedKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Decides *how* tracking runs: nothing at all in Bluetooth-only mode (the manifest receiver
 * does the work), or a foreground service once wired/playback tracking is switched on.
 */
class TrackingController(
    private val context: Context,
    private val repository: SessionRepository,
    private val settings: SettingsStore,
    /**
     * Poked once every write lands, so home-screen widgets can redraw.
     *
     * Injected rather than calling Glance directly, like [clock], to keep this layer testable
     * without a launcher. It sits *before* [clock] so trailing-lambda tests bind the clock — a
     * default on the clock instead would silently hand them the wall clock.
     */
    private val onDataChanged: suspend () -> Unit = {},
    private val clock: Clock,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Wall-clock instant the phone booted; sessions never count across it.
     *
     * Latched for the process's life, not recomputed: the wall clock steps when the phone is
     * corrected — typically an NTP sync a minute or two after a no-network boot — while
     * `elapsedRealtime` does not, so a fresh subtraction would drag the answer forward each time.
     * `reconcile` treats this as hard truth: a session opened after boot would otherwise read
     * `connectedAt < bootAt` and get force-closed as RECOVERED while the headphones are still on.
     */
    val bootAt: Long by lazy { clock.now() - SystemClock.elapsedRealtime() }

    /**
     * Whether the foreground service can legally start right now.
     *
     * On Android 14+ the `connectedDevice` service type needs a Bluetooth permission. The
     * classic flavor also declares `specialUse` and is never blocked; the Play flavor drops that
     * type, so detailed tracking there needs Bluetooth access even for wired-only users.
     */
    fun detailedTrackingSupported(): Boolean =
        Distribution.HAS_SPECIAL_USE_FGS ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            hasBluetoothPermission()

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Re-reads what is connected, repairs the database, then makes sure the right background
     * machinery is running. Safe to call as often as needed.
     *
     * On IO because two of six callers are not: `AudioManager.getDevices`, `checkSelfPermission`
     * and `WorkManager.getInstance` are binder round-trips, and `TrackingService.onCreate` and
     * the view model call this from `Dispatchers.Main`. Room dispatches its own work, so the IPC
     * was the problem, not the database.
     */
    suspend fun syncWithSystem(): Unit = withContext(Dispatchers.IO) {
        val requested = settings.detailedTrackingNow()
        val detailed = requested && detailedTrackingSupported()
        if (requested && !detailed) {
            // Bluetooth access was revoked after the fact on a build without `specialUse`: fall
            // back to Bluetooth-only rather than leave wired sessions running forever. The service
            // likely died with the permission and the whole process, so nothing has watched that
            // session since — hence `stillLive = false`, ending it at its last heartbeat rather
            // than crediting the unwatched gap. That guessed end time is what RECOVERED means to
            // the session list.
            repository.closeSessionsForKinds(
                setOf(DeviceKind.WIRED, DeviceKind.USB),
                reason = EndReason.RECOVERED,
                stillLive = false,
            )
        }
        val connected = AudioDevices.currentHeadphones(audioManager, trackedKinds(detailed))
        repository.reconcile(
            connected = connected,
            now = clock.now(),
            bootAt = bootAt,
            measurePlayback = detailed,
        )
        if (detailed) startService() else stopService()
        updateHeartbeatWork()
        onDataChanged()
    }

    /** @return false if this build cannot run the service right now; nothing was changed. */
    suspend fun setDetailedTracking(enabled: Boolean): Boolean {
        if (enabled && !detailedTrackingSupported()) return false
        settings.setDetailedTracking(enabled)
        if (!enabled) {
            // Only the service can see these; leaving them open would count forever.
            repository.closeSessionsForKinds(setOf(DeviceKind.WIRED, DeviceKind.USB))
        }
        syncWithSystem()
        return true
    }

    /** Called after a connect event so the right helper is running. */
    suspend fun onSessionOpened(detailedTracking: Boolean) {
        if (detailedTracking) startService()
        onSessionsChanged()
    }

    /** Called after a disconnect event; drops the heartbeat when there is nothing to watch. */
    suspend fun onSessionClosed() {
        onSessionsChanged()
    }

    /**
     * The bookkeeping after any session write: the heartbeat exists only while something is
     * open, and the home screen has no other way to find out.
     *
     * Separate from [onSessionOpened] because [TrackingService] writes wired connects and
     * disconnects itself, and routing those through `onSessionOpened` would ask the platform to
     * start a service it's already running in. Before this existed they went unannounced:
     * plugging in headphones with detailed tracking on updated the app and notification while
     * widgets kept yesterday's figures until something else refreshed them.
     */
    suspend fun onSessionsChanged() {
        updateHeartbeatWork()
        onDataChanged()
    }

    /**
     * A figure changed but the set of open sessions did not — the service's own minute tick,
     * where playback accrued. Does not touch the heartbeat: re-enqueuing the periodic work every
     * minute would write to WorkManager's database for nothing.
     */
    suspend fun onFiguresChanged() {
        onDataChanged()
    }

    fun startService() {
        val intent = Intent(context, TrackingService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android 12+ refuses background FGS starts outside allowed windows; the next boot,
            // app launch or heartbeat pass brings it up instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                Log.i(TAG, "Deferring tracking service start: ${e.message}")
            } else {
                throw e
            }
        }
    }

    fun stopService() {
        context.stopService(Intent(context, TrackingService::class.java))
    }

    /**
     * A missed ACL_DISCONNECTED (link loss, battery death, force-stop) would leave a session open
     * forever, so a 15-minute periodic check bounds that. Exists only while a session is open.
     *
     * Runs in both modes. Detailed tracking's foreground service is the better watcher while
     * alive, but an OEM battery manager killing it leaves that mode with no safety net — worse
     * than no service. Against a service ticking every minute the marginal cost is nothing, and
     * the worker's `syncWithSystem` also attempts `startService()`, doubling as what brings a
     * killed service back; on Android 12+ that start may be refused from a worker and merely
     * logged, but the reconcile lands either way.
     */
    private suspend fun updateHeartbeatWork() {
        val workManager = WorkManager.getInstance(context)
        if (repository.hasOpenSessions()) {
            workManager.enqueueUniquePeriodicWork(
                HeartbeatWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            workManager.cancelUniqueWork(HeartbeatWorker.NAME)
        }
    }

    private companion object {
        const val TAG = "TrackingController"
    }
}
