package it.eldavo.ylih.tracking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The whole of Bluetooth tracking. `ACL_CONNECTED`/`ACL_DISCONNECTED` are on Android's
 * implicit-broadcast exception list, so this manifest receiver still runs on modern releases
 * with nothing of ours resident — no service, no notification, no battery cost.
 */
class BtConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        ) ?: return
        val identity = AudioDevices.identityOf(device) ?: return

        val container = (context.applicationContext as YlihApp).container
        val now = container.clock.now()
        val pending = goAsync()
        container.scope.launch {
            try {
                val detailed = container.settings.detailedTrackingNow()
                if (connected) {
                    container.repository.onConnected(identity, now, measurePlayback = detailed)
                    container.trackingController.onSessionOpened(detailed)
                } else {
                    container.repository.onDisconnected(identity.key, now)
                    container.trackingController.onSessionClosed()
                }
            } catch (e: Exception) {
                // Rethrows only if *this* coroutine was cancelled, which means the scope is going
                // away. Room cancels the continuation to report a transaction it could not start
                // — a closed database — so a cancellation with the job still active is a genuine
                // failure wearing the wrong clothes, and belongs in the log like any other.
                currentCoroutineContext().ensureActive()
                Log.e(TAG, "Failed to record ${identity.key}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BtConnectionReceiver"
    }
}
