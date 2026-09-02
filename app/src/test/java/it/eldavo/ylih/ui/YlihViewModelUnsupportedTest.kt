package it.eldavo.ylih.ui

import android.Manifest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The view model on a build that cannot run the foreground service: the play flavor on Android
 * 14+ with `BLUETOOTH_CONNECT` denied, which is the one route left to that state now the floor is
 * Android 8. Turning detailed tracking on has to be refused *and* said out loud: a switch that
 * springs back with no explanation reads as a bug in the app rather than a limit of the phone.
 *
 * The classic flavor declares `specialUse` and is never blocked, so the test assumes its way past
 * there — [SettingsScreenUnsupportedTest] is where that half is asserted.
 *
 * Its own class rather than a method on [YlihViewModelTest] because Robolectric puts one sandbox
 * behind an SDK level, and this one needs the permission denied for the whole class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class YlihViewModelUnsupportedTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    private lateinit var viewModel: YlihViewModel

    @Before
    fun setUp() {
        // Before Dispatchers.setMain, for the reason YlihViewModelTest gives: the helper
        // initialises through the main dispatcher and never completes on a test one.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Denied, which is the one route left to the unavailable state: on Android 14+ the
        // connectedDevice service type requires a Bluetooth permission, and the play flavor
        // declares no specialUse type to fall back on.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        viewModel = YlihViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a build that cannot run the service says so rather than leaving the user guessing`() = runTest {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
        app.container.settings.setDetailedTracking(false)

        viewModel.setDetailedTracking(true).join()

        assertFalse(viewModel.detailedTrackingSupported.value)
        assertEquals(
            app.getString(R.string.detailed_needs_bluetooth),
            viewModel.messages.first(),
        )
        assertFalse("and nothing was written", app.container.settings.detailedTrackingNow())
    }
}
