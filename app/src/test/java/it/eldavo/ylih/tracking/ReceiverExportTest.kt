package it.eldavo.ylih.tracking

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.YlihApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins `android:exported` on the three manifest receivers, because nothing else can.
 *
 * The Bluetooth stack is a separate app (uid 1002), and an implicit broadcast from another app does
 * not resolve a non-exported component of ours — AMS drops it before the `BroadcastRecord` exists.
 * So `exported="false"` on either Bluetooth receiver silently deletes Bluetooth-only tracking, the
 * default mode, and charge cycles with it. That attribute has now been wrong in two shipped builds
 * in opposite directions, which is why it is worth a test rather than a comment.
 *
 * What makes it invisible everywhere else is that no other test can see it. [ReceiversTest]
 * dispatches through the real manifest filters and passes either way, because Robolectric hands the
 * intent to the receiver itself rather than running AMS's resolution; on a device the failure is not
 * an exception but a broadcast that never arrives, and the app carries on looking healthy — with
 * detailed tracking on, `TrackingService.syncWithSystem()` reconciles the same sessions into place
 * a minute later. So the attribute is asserted here directly, off the merged manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ReceiverExportTest {

    private val context = ApplicationProvider.getApplicationContext<YlihApp>()

    private fun exportedOf(receiver: Class<*>): Boolean {
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers
            .orEmpty()
            .singleOrNull { it.name == receiver.name }
        // A rename that the manifest did not follow would otherwise read as a passing test.
        assertNotNull("${receiver.name} is not declared in the merged manifest", declared)
        return declared!!.exported
    }

    @Test
    fun `both bluetooth receivers are exported`() {
        assertTrue(
            "BtConnectionReceiver is non-exported, so the Bluetooth stack cannot reach it and " +
                "Bluetooth-only tracking records nothing at all",
            exportedOf(BtConnectionReceiver::class.java),
        )
        assertTrue(
            "BtBatteryReceiver is non-exported, so no headset battery level is ever filed and " +
                "charge cycles stay empty forever",
            exportedOf(BtBatteryReceiver::class.java),
        )
    }

    @Test
    fun `the boot receiver is not exported`() {
        // The contrast that explains the two above: BOOT_COMPLETED and MY_PACKAGE_REPLACED come
        // from system_server (uid 1000), which is exempt from the export check, so this one loses
        // nothing by staying closed. It is the sender's uid that decides, not the flags or the
        // receiver permission — those are identical across all three broadcasts.
        assertFalse(
            "BootReceiver has no reason to be reachable by other apps",
            exportedOf(BootReceiver::class.java),
        )
    }
}
