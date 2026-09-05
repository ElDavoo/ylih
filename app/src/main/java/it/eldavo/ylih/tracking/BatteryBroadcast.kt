package it.eldavo.ylih.tracking

/**
 * The Bluetooth stack's battery broadcast, which the public SDK does not expose.
 *
 * `BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED` and its extra are `@SystemApi`, so the strings are
 * written out here rather than referenced. Depending on a hidden name is normally the wrong answer,
 * and the reasons it is the right one here are worth keeping, because every one of them was checked
 * against AOSP rather than assumed:
 *
 * - **We are allowed to hear it.** `RemoteDevices.sendBatteryLevelChangedBroadcast` sends it as
 *   `sendBroadcast(intent, BLUETOOTH_CONNECT, …)`. The receiver permission is one this app already
 *   holds for the ACL broadcasts; it is not `BLUETOOTH_PRIVILEGED`, which is what would put it out
 *   of reach.
 * - **A manifest receiver is enough.** It carries `FLAG_RECEIVER_INCLUDE_BACKGROUND`, the flag
 *   `BroadcastSkipPolicy.disallowBackgroundStart` reads — the same mechanism that lets
 *   [BtConnectionReceiver] run with nothing of this app resident. The stack sets identical
 *   delivery flags and the same receiver permission on the ACL broadcast and on this one, so it
 *   very probably reaches a non-exported receiver too; that has not been checked on a device the
 *   way the ACL case has, which is why the manifest still declares this one `exported="true"`
 *   rather than assume it.
 * - **Nobody can forge it.** It is declared `<protected-broadcast>` in the framework's own manifest,
 *   so only the system may send it and no other app can write a battery level into our database —
 *   which is also what makes exporting the receiver free of consequence.
 *
 * What is *not* guaranteed is that any given headset produces one. Battery reaches the stack over
 * HFP's battery indicator, Apple's `AT+IPHONEACCEV`, or BLE's battery service, and plenty of
 * headphones speak none of them. So this is a feature that appears when it can and is invisible
 * otherwise — never a promise made up front.
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
