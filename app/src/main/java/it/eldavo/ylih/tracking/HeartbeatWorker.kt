package it.eldavo.ylih.tracking

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bluetooth-only safety net: confirms open sessions are still real and closes the ones that
 * are not, so a missed disconnect costs at most one interval instead of running forever.
 * [TrackingController] only keeps it scheduled while a session is open.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as YlihApp).container
        return try {
            // syncWithSystem's reconcile already heartbeats every session it still finds connected,
            // and closes the ones it does not, so a second pass writes the same rows the same values.
            container.trackingController.syncWithSystem()
            Result.success()
        } catch (e: Exception) {
            // Rethrows only a cancellation of this worker's own job; Room reports a transaction it
            // could not start the same way, and that one is a failure to retry. See Cancellation.kt.
            currentCoroutineContext().ensureActive()
            // This is the only thing watching an open session when the service is dead, so a pass
            // that failed and said nothing is the worst outcome available. Retry rather than
            // report failure: a transient SecurityException from the audio stack, or a database
            // busy for a moment, costs one interval instead of a whole period.
            Log.e(TAG, "Heartbeat sync failed", e)
            Result.retry()
        }
    }

    companion object {
        const val NAME = "ylih-heartbeat"

        private const val TAG = "HeartbeatWorker"
    }
}
