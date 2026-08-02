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
     * Poked once every write has landed, so the home-screen widgets can redraw.
     *
     * Injected rather than calling Glance from here for the same reason [clock] is injected: it
     * keeps the policy layer testable without a launcher. It sits *before* [clock] deliberately —
     * that leaves the trailing-lambda call the tests use binding to the clock, where a default on
     * the clock instead would silently hand them the wall clock.
     */
    private val onDataChanged: suspend () -> Unit = {},
    private val clock: Clock,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    /** Wall-clock instant the phone booted; sessions are never counted across it. */
    fun bootAt(): Long = clock.now() - SystemClock.elapsedRealtime()

    /**
     * Whether the foreground service can legally start right now.
     *
     * Notification channels don't exist before Android 8 (API 26), and the service's whole
     * point is its persistent notification, so detailed tracking is unavailable below that —
     * those installs get Bluetooth-only tracking regardless of the setting.
     *
     * On Android 14+ the `connectedDevice` service type requires holding a Bluetooth
     * permission. The classic flavor also declares `specialUse` and so is never blocked; the
     * Play flavor drops that type, which means detailed tracking there needs Bluetooth access
     * even when the user only cares about wired headphones.
     */
    fun detailedTrackingSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (
                Distribution.HAS_SPECIAL_USE_FGS ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    hasBluetoothPermission()
                )

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Re-reads what is actually connected and repairs the database, then makes sure the right
     * background machinery is running. Safe to call as often as we like.
     */
    suspend fun syncWithSystem() {
        val requested = settings.detailedTrackingNow()
        val detailed = requested && detailedTrackingSupported()
        if (requested && !detailed) {
            // Bluetooth access was revoked after the fact on a build without `specialUse`:
            // fall back to Bluetooth-only rather than leaving wired sessions running forever.
            // The service died with the permission, most likely along with the whole process, so
            // nothing has watched that session since — hence `stillLive = false`, which ends it at
            // its last heartbeat rather than crediting the unwatched gap. That end time is a
            // guess, which is what RECOVERED means to the session list.
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
            bootAt = bootAt(),
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
     * The bookkeeping that follows any session write: the heartbeat exists only while something is
     * open, and the home screen has no other way to find out.
     *
     * Separate from [onSessionOpened] for the caller that is already the service — [TrackingService]
     * writes wired connects and disconnects itself, and routing those through `onSessionOpened`
     * would have it ask the platform to start the service it is running in. Before this existed
     * they went unannounced, so plugging headphones in with detailed tracking on changed the app
     * and the notification while the widgets kept yesterday's figures until something else
     * happened to refresh them.
     */
    suspend fun onSessionsChanged() {
        updateHeartbeatWork()
        onDataChanged()
    }

    /**
     * A figure changed but the set of open sessions did not — the service's own minute tick, where
     * playback has accrued. Deliberately does not touch the heartbeat: re-enqueuing the periodic
     * work every minute would write to WorkManager's database for nothing.
     */
    suspend fun onFiguresChanged() {
        onDataChanged()
    }

    fun startService() {
        val intent = Intent(context, TrackingService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android 12+ refuses background FGS starts outside the allowed windows; the next
            // boot, app launch or heartbeat pass will bring it up instead.
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
     * forever, so a 15-minute periodic check bounds that error. It only exists while a session is
     * actually open.
     *
     * It runs in *both* modes. Detailed tracking's foreground service is the better watcher while
     * it is alive, but an OEM battery manager that kills it would otherwise leave that mode with
     * no safety net at all — strictly worse than the mode with no service. Against a service
     * already ticking every minute the marginal cost is nothing, and the worker's `syncWithSystem`
     * attempts `startService()` as well, so it doubles as the thing that brings a killed service
     * back; on Android 12+ that start may be refused from a worker and merely logged, but the
     * reconcile lands either way.
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
