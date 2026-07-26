package it.eldavo.ylih.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The enums are persisted by name, so a value that stops round-tripping silently rewrites
 * history on the next read. An unknown name has to degrade rather than throw: a backup written
 * by a newer build is exactly the case that produces one.
 */
class EntitiesTest {

    private val converters = Converters()

    @Test
    fun `every device kind survives a round trip through the database`() {
        for (kind in DeviceKind.entries) {
            assertEquals(
                kind,
                converters.stringToDeviceKind(converters.deviceKindToString(kind)),
            )
        }
    }

    @Test
    fun `every end reason survives a round trip through the database`() {
        for (reason in EndReason.entries) {
            assertEquals(
                reason,
                converters.stringToEndReason(converters.endReasonToString(reason)),
            )
        }
    }

    @Test
    fun `an unrecognised kind reads back as unknown rather than throwing`() {
        assertEquals(DeviceKind.UNKNOWN, DeviceKind.parse("SATELLITE"))
        assertEquals(DeviceKind.UNKNOWN, DeviceKind.parse(null))
        assertEquals(DeviceKind.UNKNOWN, converters.stringToDeviceKind(null))
    }

    @Test
    fun `an unrecognised end reason reads back as no reason at all`() {
        assertNull(EndReason.parse("EXPLODED"))
        assertNull(EndReason.parse(null))
        assertNull(converters.endReasonToString(null))
        assertNull(converters.stringToEndReason("EXPLODED"))
    }

    @Test
    fun `bluetooth-only mode tracks fewer kinds than detailed tracking`() {
        val bluetoothOnly = trackedKinds(detailedTracking = false)
        val detailed = trackedKinds(detailedTracking = true)

        assertEquals(setOf(DeviceKind.BLUETOOTH, DeviceKind.BLE), bluetoothOnly)
        // Wired and USB are the two the foreground service exists for.
        assertEquals(
            setOf(DeviceKind.WIRED, DeviceKind.USB),
            detailed - bluetoothOnly,
        )
    }
}
