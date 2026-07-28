package it.eldavo.ylih.tracking

import android.Manifest
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Which foreground-service type the service claims decides whether Android lets it run at all,
 * and the answer differs by flavor *and* by release — which is exactly the combination nobody
 * checks by hand. [it.eldavo.ylih.DistributionTest] pins what the manifest declares; this pins
 * what the service actually asks for.
 *
 * Separate from [TrackingServiceTest] because these run across several SDK levels, and that one
 * grants the Bluetooth permission its whole setup depends on.
 */
@RunWith(RobolectricTestRunner::class)
class TrackingServiceForegroundTypeTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    private lateinit var controller: ServiceController<TrackingService>
    private val service get() = controller.get()

    @Before
    fun setUp() = runBlocking {
        // syncWithSystem() reaches WorkManager, whose androidx.startup initializer never runs here.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.container.database.deviceDao().deleteAll()
        app.container.settings.setDetailedTracking(true)
        controller = Robolectric.buildService(TrackingService::class.java)
    }

    @After
    fun tearDown() = runBlocking {
        controller.destroy()
        // The preferences file is real and the DataStore behind it is a process singleton.
        app.container.settings.setDetailedTracking(false)
    }

    /** The type is recorded the moment the service goes foreground, in `onCreate`. */
    private fun typeClaimedAtStartup(): Int {
        controller.create()
        return service.foregroundServiceType
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `denied bluetooth costs the classic build its type but not its service`() {
        // From Android 14 `connectedDevice` requires a Bluetooth permission. Someone who only
        // wants wired headphones tracked should not have to grant Bluetooth to get it, which is
        // the entire reason the sideloaded build also declares `specialUse`.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(
            if (Distribution.HAS_SPECIAL_USE_FGS) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                // The Play build gave `specialUse` up, so it has nothing to fall back to —
                // TrackingController.detailedTrackingSupported() is what keeps it from starting.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            },
            typeClaimedAtStartup(),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `before android 14 there is nothing to fall back to and nothing to fall back from`() {
        // `connectedDevice` carries no permission requirement here, so the fallback never
        // applies however the Bluetooth permission stands.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            typeClaimedAtStartup(),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `with bluetooth access every build is a connected-device service`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            typeClaimedAtStartup(),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `before android 10 a foreground service has no type to declare`() {
        // Types arrive in Android 10; asking for one before that is what throws. There is no
        // getForegroundServiceType() to read on this release either, so the notification going
        // up at all is the assertion.
        controller.create()

        assertNotNull(shadowOf(service).lastForegroundNotification)
    }
}
