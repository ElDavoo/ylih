package it.eldavo.ylih.tracking

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.data.DeviceIdentity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.trackedKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Device identity is the invariant the whole app rests on: if the two platform views of one
 * headset disagree, its lifetime hours silently split across two pairs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AudioDevicesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val name = "ACCENTUM Plus"
    private val mac = "80:C3:BA:A6:5E:C2"
    private val redactedMac = "XX:XX:XX:XX:5E:C2"

    /** `BluetoothClass` is built from the packed class-of-device integer the stack reports. */
    private fun bluetoothClass(classInt: Int): BluetoothClass = ReflectionHelpers.callConstructor(
        BluetoothClass::class.java,
        ClassParameter.from(Int::class.javaPrimitiveType, classInt),
    )

    private fun remoteDevice(address: String): BluetoothDevice = context
        .getSystemService(BluetoothManager::class.java)
        .adapter
        .getRemoteDevice(address)

    private fun bluetoothDevice(
        address: String = mac,
        deviceName: String? = name,
        classInt: Int = AUDIO_VIDEO_HEADPHONES,
        type: Int = BluetoothDevice.DEVICE_TYPE_CLASSIC,
    ): BluetoothDevice {
        val device = remoteDevice(address)
        shadowOf(device).setBluetoothClass(bluetoothClass(classInt))
        shadowOf(device).setName(deviceName)
        shadowOf(device).setType(type)
        return device
    }

    @Test
    fun `a redacted and a full address describe the same headset`() {
        // Caught on a real Android 16 device: AudioDeviceInfo.getAddress() redacts the first
        // four octets while the ACL broadcast reports the whole MAC, and the app recorded the
        // one pair of headphones twice.
        assertEquals(
            AudioDevices.bluetoothKey("80:C3:BA:A6:5E:C2", name),
            AudioDevices.bluetoothKey("XX:XX:XX:XX:5E:C2", name),
        )
    }

    @Test
    fun `address case does not change the identity`() {
        assertEquals(
            AudioDevices.bluetoothKey("80:c3:ba:a6:5e:c2", name),
            AudioDevices.bluetoothKey("80:C3:BA:A6:5E:C2", name),
        )
    }

    @Test
    fun `different headsets stay different`() {
        assertNotEquals(
            AudioDevices.bluetoothKey("80:C3:BA:A6:5E:C2", name),
            AudioDevices.bluetoothKey("80:C3:BA:A6:11:22", "Other"),
        )
    }

    @Test
    fun `a fully redacted address falls back to the product name`() {
        val key = AudioDevices.bluetoothKey("XX:XX:XX:XX:XX:XX", name)
        assertEquals("bt:name:$name", key)
    }

    @Test
    fun `an empty address still yields an identity when a name is known`() {
        assertNotNull(AudioDevices.bluetoothKey("", name))
        assertNotNull(AudioDevices.bluetoothKey(null, name))
    }

    @Test
    fun `nothing identifiable yields no identity at all`() {
        assertNull(AudioDevices.bluetoothKey(null, null))
        assertNull(AudioDevices.bluetoothKey("", " "))
    }

    @Test
    fun `both platform views of one headset agree on the identity`() {
        val fromTheAudioStack = AudioDevices.identityOf(
            outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, redactedMac, name),
        )
        val fromTheAclBroadcast = AudioDevices.identityOf(bluetoothDevice())

        assertEquals(DeviceIdentity("bt:5E:C2", DeviceKind.BLUETOOTH, name), fromTheAudioStack)
        assertEquals(fromTheAudioStack, fromTheAclBroadcast)
    }

    @Test
    fun `a low-energy headset is recorded as its own kind`() {
        assertEquals(
            DeviceKind.BLE,
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, redactedMac, name),
            )?.kind,
        )
        assertEquals(
            DeviceKind.BLE,
            AudioDevices.identityOf(
                bluetoothDevice(type = BluetoothDevice.DEVICE_TYPE_LE),
            )?.kind,
        )
    }

    @Test
    fun `a bluetooth sink with neither address nor name is skipped`() {
        ShadowBuild.setModel("")

        assertNull(AudioDevices.identityOf(outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun `an unnamed bluetooth sink is labelled with the address it did report`() {
        ShadowBuild.setModel("")

        assertEquals(
            DeviceIdentity("bt:5E:C2", DeviceKind.BLUETOOTH, redactedMac),
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, redactedMac),
            ),
        )
    }

    @Test
    fun `the two wired types are distinct pairs, not one`() {
        assertEquals(
            DeviceIdentity("wired:headset", DeviceKind.WIRED, "Some brand"),
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, productName = "Some brand"),
            ),
        )
        assertEquals(
            "wired:headphones",
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = "Some brand"),
            )?.key,
        )
    }

    @Test
    fun `wired headphones the platform will not name still get a label`() {
        // Android reports no product name for most analogue plugs, so the fallback is the only
        // thing the card is ever drawn with.
        ShadowBuild.setModel("")

        assertEquals(
            DeviceIdentity("wired:headset", DeviceKind.WIRED, "Wired headset"),
            AudioDevices.identityOf(outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET)),
        )
        assertEquals(
            DeviceIdentity("wired:headphones", DeviceKind.WIRED, "Wired headphones"),
            AudioDevices.identityOf(outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)),
        )
    }

    @Test
    fun `usb headphones are keyed by name because the bus offers nothing else`() {
        assertEquals(
            DeviceIdentity("usb:Apple USB-C", DeviceKind.USB, "Apple USB-C"),
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_USB_HEADSET, productName = "Apple USB-C"),
            ),
        )
        assertEquals(
            DeviceIdentity("usb:Apple USB-C", DeviceKind.USB, "Apple USB-C"),
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_USB_DEVICE, productName = "Apple USB-C"),
            ),
        )
    }

    @Test
    fun `an unnamed usb output still resolves to one stable key`() {
        ShadowBuild.setModel("")

        assertEquals(
            DeviceIdentity("usb:device", DeviceKind.USB, "USB headphones"),
            AudioDevices.identityOf(outputDevice(AudioDeviceInfo.TYPE_USB_HEADSET)),
        )
    }

    @Test
    fun `outputs that are not headphones are not tracked`() {
        assertNull(
            "the phone's own speaker",
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Speaker"),
            ),
        )
        assertNull(
            "a microphone is a source, not a sink",
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, productName = name, role = ROLE_SOURCE),
            ),
        )
    }

    @Test
    fun `bluetooth audio devices that are furniture are not headphones`() {
        assertNull("a car stereo", AudioDevices.identityOf(bluetoothDevice(classInt = AUDIO_VIDEO_CAR_AUDIO)))
        assertNull("a phone", AudioDevices.identityOf(bluetoothDevice(classInt = PHONE_CELLULAR)))
    }

    @Test
    fun `a bluetooth device that reports no class at all is given the benefit of the doubt`() {
        // Some headsets answer the class query with nothing; refusing those would lose sessions
        // for a real pair of headphones.
        val device = remoteDevice(mac)
        shadowOf(device).setName(name)

        assertEquals(
            DeviceIdentity("bt:5E:C2", DeviceKind.BLUETOOTH, name),
            AudioDevices.identityOf(device),
        )
    }

    @Test
    fun `a nameless bluetooth device is labelled with its address`() {
        assertEquals(mac, AudioDevices.identityOf(bluetoothDevice(deviceName = null))?.name)
        assertEquals(mac, AudioDevices.identityOf(bluetoothDevice(deviceName = " "))?.name)
    }

    @Test
    fun `a headset the app may no longer ask about is still counted`() {
        // Revoking BLUETOOTH_CONNECT makes every guarded getter throw. Each one is wrapped
        // separately so that costs the *name*, not the identity: resolving to null here would
        // start the pair's lifetime again from zero the next time it connected.
        val device = bluetoothDevice()
        shadowOf(device).setShouldThrowSecurityExceptions(true)

        val identity = AudioDevices.identityOf(device)

        assertEquals("bt:5E:C2", identity?.key)
        assertEquals("the address is the only label left", mac, identity?.name)
        assertEquals("an unreadable type is a plain headset, not a skipped one", DeviceKind.BLUETOOTH, identity?.kind)
    }

    @Test
    @Config(shadows = [ShadowAddresslessBluetoothDevice::class])
    fun `a device the app cannot read at all is skipped rather than crashed`() {
        // The other half of the same promise: with nothing identifiable left there is no session
        // to record, and a manifest receiver that threw here would take the broadcast down.
        val device = bluetoothDevice()
        shadowOf(device).setShouldThrowSecurityExceptions(true)

        assertNull(AudioDevices.identityOf(device))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O_MR1])
    fun `before android 9 the audio stack discloses no address at all`() {
        // AudioDeviceInfo.getAddress() only exists from API 28, so on older installs the product
        // name is the whole of a Bluetooth output's identity.
        assertEquals(
            "bt:name:$name",
            AudioDevices.identityOf(
                outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, redactedMac, name),
            )?.key,
        )
    }

    @Test
    fun `what is connected right now is filtered to the kinds this mode tracks`() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setOutputDevices(
            listOf(
                outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, productName = "Speaker"),
                outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, productName = "Plugged in"),
                outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, redactedMac, name),
                // The same headset again as an LE output, which real phones do report.
                outputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, redactedMac, name),
            ),
        )

        assertEquals(
            listOf("bt:5E:C2"),
            AudioDevices.currentHeadphones(audioManager, setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE))
                .map { it.key },
        )
        assertEquals(
            listOf("wired:headphones", "bt:5E:C2"),
            AudioDevices.currentHeadphones(audioManager, trackedKinds(detailedTracking = true))
                .map { it.key },
        )
    }

    private companion object {
        /** Packed class-of-device values, as `BluetoothClass` unpacks them. */
        const val AUDIO_VIDEO_HEADPHONES = 0x240418
        const val AUDIO_VIDEO_CAR_AUDIO = 0x240420
        const val PHONE_CELLULAR = 0x5A020C
    }
}

/**
 * `setShouldThrowSecurityExceptions` covers the getters [ShadowBluetoothDevice] implements
 * itself, but not `getAddress()` — and the address is what decides whether a device can be
 * identified at all once the name has gone with it.
 */
@Implements(BluetoothDevice::class)
class ShadowAddresslessBluetoothDevice : ShadowBluetoothDevice() {
    @Implementation
    fun getAddress(): String = throw SecurityException("BLUETOOTH_CONNECT denied")
}
