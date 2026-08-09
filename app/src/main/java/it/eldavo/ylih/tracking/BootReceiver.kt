package it.eldavo.ylih.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Closes whatever the shutdown never told us about, and brings the service back in detailed
 * mode. Sessions that predate this boot are closed at their last heartbeat — the hours the
 * phone spent switched off are never counted as listening time.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Unit

            else -> return
        }

        val container = (context.applicationContext as YlihApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                container.trackingController.syncWithSystem()
            } catch (e: Exception) {
                // See BtConnectionReceiver: a cancellation with this job still active is Room
                // reporting a transaction it could not start, not the scope shutting down.
                currentCoroutineContext().ensureActive()
                Log.e(TAG, "Boot reconcile failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
