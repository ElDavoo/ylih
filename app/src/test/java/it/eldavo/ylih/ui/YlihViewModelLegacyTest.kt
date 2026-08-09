package it.eldavo.ylih.ui

import android.Manifest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The view model at the minSdk floor, where no build can run the foreground service because
 * notification channels do not exist yet. Turning detailed tracking on has to be refused *and*
 * said out loud: a switch that springs back with no explanation reads as a bug in the app rather
 * than a limit of the phone.
 *
 * Its own class rather than a method on [YlihViewModelTest] because Robolectric puts one sandbox
 * behind an SDK level, and that one runs on 34.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.M])
class YlihViewModelLegacyTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    private lateinit var viewModel: YlihViewModel

    @Before
    fun setUp() {
        // Before Dispatchers.setMain, for the reason YlihViewModelTest gives: the helper
        // initialises through the main dispatcher and never completes on a test one.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Granted so the refusal below can only be about the platform version.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        viewModel = YlihViewModel(app)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a phone too old for the service is told so rather than left guessing`() = runTest {
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
