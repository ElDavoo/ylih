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
 * Turns the platform's two views of a headphone — [AudioDeviceInfo] from the audio stack,
 * [BluetoothDevice] from the ACL broadcast — into one stable identity.
 *
 * Both yield the same `bt:<MAC>` key for the same Bluetooth headset, so the receiver and the
 * service never open two sessions for one pair.
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
     * The last two octets of `02:00:00:00:00:00`, the address `BluetoothDevice.getAddress()`
     * gives an app without BLUETOOTH_CONNECT from API 31 — which at face value would key every
     * headset to `bt:00:00`, merging everyone's hours. Not reachable today: the ACL broadcast
     * needs the same permission, and the audio stack's redaction keeps these two octets instead
     * of zeroing them (`XX:XX:XX:XX:5E:C2`, why they're the key at all). Refused anyway in one
     * line: being wrong here merges every pair's history with no way to unpick it.
     */
    private const val ANONYMISED_SUFFIX = "00:00"

    /**
     * The two views report different addresses: `AudioDeviceInfo.getAddress()` gives a partially
     * redacted MAC (`XX:XX:XX:XX:5E:C2`), the ACL broadcast the full `80:C3:BA:A6:5E:C2` (Android
     * 16). The last two octets are the only part both disclose, so they identify the device; two
     * paired headsets would need to collide on the final 16 bits to be confused.
     */
    fun bluetoothKey(address: String?, name: String?): String? {
        val suffix = address
            ?.split(":")
            ?.filter { it.isNotBlank() }
            ?.takeLast(2)
            ?.joinToString(":") { it.uppercase() }
            ?.takeIf { MAC_SUFFIX.matches(it) && it != ANONYMISED_SUFFIX }
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

            // SCO alongside A2DP: a headset carrying calls but not media — a mono call headset,
            // or one whose media profile hasn't come up yet — reports only as SCO. Omitting it
            // made it invisible to [currentHeadphones], so `reconcile` closed a session still
            // live at its last heartbeat.
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
