package it.eldavo.ylih

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.tracking.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `play` flavor's whole point is a smaller manifest. Runs under both flavors, so a
 * constant and the manifest it describes cannot drift apart unnoticed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DistributionTest {

    private val context = ApplicationProvider.getApplicationContext<YlihApp>()

    @Test
    fun `special use permission is declared exactly when the flavor claims it`() {
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()

        assertEquals(
            Distribution.HAS_SPECIAL_USE_FGS,
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in declared,
        )
        assertTrue(
            "connectedDevice is what both flavors rely on",
            "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in declared,
        )
    }

    @Test
    fun `the tracking service declares the matching foreground service types`() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, TrackingService::class.java),
            0,
        )
        val types = service.foregroundServiceType
        assertTrue(
            "connectedDevice must always be declared",
            types and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0,
        )
        assertEquals(
            Distribution.HAS_SPECIAL_USE_FGS,
            types and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
    }
}
