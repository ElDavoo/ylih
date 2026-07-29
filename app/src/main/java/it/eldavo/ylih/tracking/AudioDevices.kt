package it.eldavo.ylih.tracking

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.data.DeviceKind

/**
 * Turns the platform's two different views of a headphone — [AudioDeviceInfo] from the audio
 * stack and [BluetoothDevice] from the ACL broadcast — into one stable identity.
 *
 * Both views of the same Bluetooth headset yield the same `bt:<MAC>` key, so the receiver and
 * the service can never open two sessions for one pair.
 */
object AudioDevices {

    /** Bluetooth device classes that are audio sinks but definitely not headphones. */
    private val NON_HEADPHONE_CLASSES = setOf(
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
        BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
        BluetoothClass.Device.AUDIO_VIDEO_VCR,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY,
        BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
    )

    private val MAC_SUFFIX = Regex("[0-9A-F]{2}:[0-9A-F]{2}")

    /**
     * Both platform views of a headset have to produce the same key, or one pair's hours end up
     * split across two rows.
     *
     * They do not report the same address: `AudioDeviceInfo.getAddress()` hands back a
     * partially redacted MAC (`XX:XX:XX:XX:5E:C2`) while the ACL broadcast reports the full
     * `80:C3:BA:A6:5E:C2` — observed on Android 16. The last two octets are the only part both
     * APIs disclose, so that is what identifies the device. Two paired headsets would have to
     * collide on the final 16 bits of their MAC to be confused for each other.
     */
    fun bluetoothKey(address: String?, name: String?): String? {
        val suffix = address
            ?.split(":")
            ?.filter { it.isNotBlank() }
            ?.takeLast(2)
            ?.joinToString(":") { it.uppercase() }
            ?.takeIf { MAC_SUFFIX.matches(it) }
        return when {
            suffix != null -> "bt:$suffix"
            !name.isNullOrBlank() -> "bt:name:$name"
            else -> null
        }
    }

    /**
     * @return the identity of a Bluetooth device we should track, or null for anything that
     *   is not an audio sink (watches, keyboards, car stereos, speakers).
     */
    // Every BLUETOOTH_CONNECT-guarded call below is individually wrapped, so a missing
    // permission degrades to a nameless or skipped device instead of crashing a receiver.
    @SuppressLint("MissingPermission")
    fun identityOf(device: BluetoothDevice): DeviceIdentity? {
        val btClass = runCatching { device.bluetoothClass }.getOrNull()
        val major = runCatching { btClass?.majorDeviceClass }.getOrNull()
        if (major != null && major != BluetoothClass.Device.Major.AUDIO_VIDEO) return null
        val deviceClass = runCatching { btClass?.deviceClass }.getOrNull()
        if (deviceClass != null && deviceClass in NON_HEADPHONE_CLASSES) return null

        val address = runCatching { device.address }.getOrNull().orEmpty()
        val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        val key = bluetoothKey(address, name) ?: return null
        val kind = when (runCatching { device.type }.getOrNull()) {
            BluetoothDevice.DEVICE_TYPE_LE -> DeviceKind.BLE
            else -> DeviceKind.BLUETOOTH
        }
        return DeviceIdentity(key = key, kind = kind, name = name ?: address)
    }

    /** @return the identity of an audio output that is a pair of headphones, else null. */
    fun identityOf(info: AudioDeviceInfo): DeviceIdentity? {
        if (!info.isSink) return null
        val name = info.productName?.toString()?.trim().orEmpty()
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { info.address }.getOrNull().orEmpty()
        } else {
            ""
        }

        return when (info.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                DeviceIdentity("wired:headset", DeviceKind.WIRED, name.ifEmpty { "Wired headset" })

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                DeviceIdentity("wired:headphones", DeviceKind.WIRED, name.ifEmpty { "Wired headphones" })

            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE ->
                DeviceIdentity(
                    key = "usb:${name.ifEmpty { "device" }}",
                    kind = DeviceKind.USB,
                    name = name.ifEmpty { "USB headphones" },
                )

            // SCO alongside A2DP because a headset that carries calls but not media — a mono
            // call headset, or one whose media profile has not come up yet — is only ever
            // reported as SCO. Leaving it out made it invisible to [currentHeadphones], so
            // `reconcile` closed a session that was still live at its last heartbeat.
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                bluetoothIdentity(address, name, DeviceKind.BLUETOOTH)

            AudioDeviceInfo.TYPE_BLE_HEADSET ->
                bluetoothIdentity(address, name, DeviceKind.BLE)

            else -> null
        }
    }

    private fun bluetoothIdentity(address: String, name: String, kind: DeviceKind): DeviceIdentity? {
        val key = bluetoothKey(address, name) ?: return null
        return DeviceIdentity(key, kind, name.ifEmpty { address })
    }

    /** Headphones plugged in / paired *right now*, filtered to the kinds the current mode tracks. */
    fun currentHeadphones(
        audioManager: AudioManager,
        kinds: Set<DeviceKind>,
    ): List<DeviceIdentity> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .mapNotNull { identityOf(it) }
            .filter { it.kind in kinds }
            .distinctBy { it.key }
}
