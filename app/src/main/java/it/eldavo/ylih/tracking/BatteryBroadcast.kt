package it.eldavo.ylih.tracking

/**
 * The Bluetooth stack's battery broadcast; the public SDK does not expose it.
 *
 * `BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED` and its extra are `@SystemApi`, so the strings
 * are written out here rather than referenced. Depending on a hidden name is normally wrong; each
 * reason it's right here was checked against AOSP, not assumed:
 *
 * - **We are allowed to hear it.** `RemoteDevices.sendBatteryLevelChangedBroadcast` sends it as
 *   `sendBroadcast(intent, BLUETOOTH_CONNECT, …)` — a permission this app already holds for the
 *   ACL broadcasts, not the out-of-reach `BLUETOOTH_PRIVILEGED`.
 * - **A manifest receiver is enough, if exported.** It carries `FLAG_RECEIVER_INCLUDE_BACKGROUND`
 *   — the flag `BroadcastSkipPolicy.disallowBackgroundStart` reads, letting [BtConnectionReceiver]
 *   run with nothing of this app resident. Both broadcasts carry identical delivery flags, so
 *   `android:exported` matters the same way: the sender is another app, and AMS drops a
 *   non-exported component from an implicit broadcast before the `BroadcastRecord` exists.
 * - **Nobody can forge it.** Declared `<protected-broadcast>` in the framework manifest, so only
 *   the system may send it — no other app can write a battery level into our database, which
 *   makes exporting the receiver free of consequence.
 *
 * Not guaranteed: that a given headset produces one. Battery reaches the stack over HFP's battery
 * indicator, Apple's `AT+IPHONEACCEV`, or BLE's battery service, and many headphones speak
 * none — so the feature appears when it can and is invisible otherwise.
 */
internal object BatteryBroadcast {
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    /**
     * What the extra holds when there is no answer: `BATTERY_LEVEL_UNKNOWN` is -1 and
     * `BATTERY_LEVEL_BLUETOOTH_OFF` is -100. Both are outside a percentage, so one range check
     * covers them and anything else the platform invents later.
     */
    const val LEVEL_ABSENT = -1
}
