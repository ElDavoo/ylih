package it.eldavo.ylih.tracking

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import it.eldavo.ylih.data.Clock
import it.eldavo.ylih.data.DeviceKind
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
    private val clock: Clock,
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)

    /** Wall-clock instant the phone booted; sessions are never counted across it. */
    fun bootAt(): Long = clock.now() - SystemClock.elapsedRealtime()

    /**
     * Re-reads what is actually connected and repairs the database, then makes sure the right
     * background machinery is running. Safe to call as often as we like.
     */
    suspend fun syncWithSystem() {
        val detailed = settings.detailedTrackingNow()
        val connected = AudioDevices.currentHeadphones(audioManager, trackedKinds(detailed))
        repository.reconcile(
            connected = connected,
            now = clock.now(),
            bootAt = bootAt(),
            measurePlayback = detailed,
        )
        if (detailed) startService() else stopService()
        updateHeartbeatWork(detailed)
    }

    suspend fun setDetailedTracking(enabled: Boolean) {
        settings.setDetailedTracking(enabled)
        if (!enabled) {
            // Only the service can see these; leaving them open would count forever.
            repository.closeSessionsForKinds(setOf(DeviceKind.WIRED, DeviceKind.USB))
        }
        syncWithSystem()
    }

    /** Called after a connect event so the right helper is running. */
    suspend fun onSessionOpened(detailedTracking: Boolean) {
        if (detailedTracking) startService()
        updateHeartbeatWork(detailedTracking)
    }

    /** Called after a disconnect event; drops the heartbeat when there is nothing to watch. */
    suspend fun onSessionClosed() {
        updateHeartbeatWork(settings.detailedTrackingNow())
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
     * In Bluetooth-only mode nothing of ours is running, so a missed ACL_DISCONNECTED (link
     * loss, battery death, force-stop) would leave a session open forever. A 15-minute
     * periodic check bounds that error, and only exists while a session is actually open.
     */
    private suspend fun updateHeartbeatWork(detailedTracking: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val needed = !detailedTracking && repository.hasOpenSessions()
        if (needed) {
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
