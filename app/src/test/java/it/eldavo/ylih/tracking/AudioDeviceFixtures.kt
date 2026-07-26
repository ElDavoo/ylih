package it.eldavo.ylih.tracking

import android.media.AudioDeviceInfo
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

/** `AudioPort.ROLE_SOURCE` — a microphone rather than an output. */
const val ROLE_SOURCE = 1

/** `AudioPort.ROLE_SINK` — what every pair of headphones is. */
const val ROLE_SINK = 2

/**
 * Builds the [AudioDeviceInfo] the audio stack would hand back for a connected output.
 *
 * `AudioDeviceInfoBuilder` never sets the underlying port's role, so everything it builds
 * reports `isSink == false` and [AudioDevices.identityOf] returns before it looks at the type at
 * all. It also offers no way to set the name or the address, which are the two fields an
 * identity is actually made of — hence reaching into the port directly.
 */
fun outputDevice(
    type: Int,
    address: String = "",
    productName: String = "",
    role: Int = ROLE_SINK,
): AudioDeviceInfo {
    val info = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
    val port = ReflectionHelpers.getField<Any>(info, "mPort")
    ReflectionHelpers.setField(port.javaClass.superclass, port, "mRole", role)
    ReflectionHelpers.setField(port.javaClass.superclass, port, "mName", productName)
    ReflectionHelpers.setField(port.javaClass, port, "mAddress", address)
    return info
}
