package it.eldavo.ylih.tracking

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device identity is the invariant the whole app rests on: if the two platform views of one
 * headset disagree, its lifetime hours silently split across two pairs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AudioDevicesTest {

    private val name = "ACCENTUM Plus"

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
}
