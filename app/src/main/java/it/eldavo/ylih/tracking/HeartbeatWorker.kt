package it.eldavo.ylih.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.eldavo.ylih.YlihApp

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
        container.trackingController.syncWithSystem()
        container.repository.heartbeat()
        return Result.success()
    }

    companion object {
        const val NAME = "ylih-heartbeat"
    }
}
