package it.eldavo.ylih.tracking

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Files the headset's own battery level; this is how charge cycles get counted at all.
 *
 * Shaped like [BtConnectionReceiver] — arrives on the same terms (see [BatteryBroadcast] for why
 * a hidden action can be relied on) — and is a manifest receiver for the same reason: headphones
 * report battery only a handful of times per discharge, when nothing else of this app is awake.
 *
 * It tells [TrackingController] nothing, on purpose. A battery reading changes no figure any
 * widget shows, and the heartbeat bounds a missed disconnect rather than follows the battery —
 * re-enqueuing it here would write to WorkManager's database for a number nobody reads.
 */
class BtBatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BatteryBroadcast.ACTION_BATTERY_LEVEL_CHANGED) return
        val level = intent.getIntExtra(
            BatteryBroadcast.EXTRA_BATTERY_LEVEL,
            BatteryBroadcast.LEVEL_ABSENT,
        )
        // Cheap enough to leave to the repository, refused here too so a disconnect — which
        // broadcasts "unknown" for every device every time — does not wake a coroutine and three
        // queries just to learn it had nothing to say.
        if (level !in 0..100) return
        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        ) ?: return
        // The same filter sessions go through, so a watch, car stereo or speaker announcing its
        // battery is dropped here instead of opening a row for an untracked device.
        val identity = AudioDevices.identityOf(device) ?: return

        val container = (context.applicationContext as YlihApp).container
        val now = container.clock.now()
        val pending = goAsync()
        container.scope.launch {
            try {
                recordWithRetry(container.repository, identity.key, level, now)
            } catch (e: Exception) {
                // See BtConnectionReceiver: Room reports a database it could not open by cancelling
                // the continuation, so a cancellation with the job still active is a real failure.
                currentCoroutineContext().ensureActive()
                Log.e(TAG, "Failed to record battery for ${identity.key}", e)
            } finally {
                pending.finish()
            }
        }
    }

    // Not private: `companion object` is a separate class, so a private member would be reached
    // through a synthetic accessor (lint's SyntheticAccessor).
    internal companion object {
        const val TAG = "BtBatteryReceiver"

        /**
         * How long to give a connect still being written. Measured at 68 ms on the phone below;
         * two seconds covers a cold process opening Room for the first time, and stays well
         * inside the `goAsync` window, which allows ten.
         */
        const val SESSION_SETTLE_MS = 2_000L

        /**
         * Files [level], retrying once if the session it belongs to has not been written yet.
         *
         * The first reading of a session normally arrives *before* the session does, often the
         * only reading a headset ever sends. Measured on a Mi 10T running Android 16:
         * ACL_CONNECTED at 08:20:52.721, this broadcast at 08:20:53.131, and
         * [BtConnectionReceiver]'s own row at 08:20:53.199 — 68 ms too late, so the reading was
         * dropped. The two receivers race by construction: that one reads the tracking mode
         * before writing, and both hand their work to the same scope.
         *
         * A reading still finding no session after the retry belongs to an untracked device and
         * is dropped.
         *
         * [settleMs] lets the retry run in virtual time; nothing in the app passes it explicitly.
         */
        internal suspend fun recordWithRetry(
            repository: SessionRepository,
            key: String,
            level: Int,
            at: Long,
            settleMs: Long = SESSION_SETTLE_MS,
        ) {
            if (repository.recordBatteryLevel(key, level, at)) return
            delay(settleMs)
            repository.recordBatteryLevel(key, level, at)
        }
    }
}
