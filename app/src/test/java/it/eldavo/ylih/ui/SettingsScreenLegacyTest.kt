package it.eldavo.ylih.ui

import android.Manifest
import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings as the minSdk floor sees it. Detailed tracking needs a notification channel, which
 * arrived in Android 8, so an API 23 install can never have it — and unlike the revoked-Bluetooth
 * route (which only the Play build can reach) this is the same on both flavors.
 *
 * The screen must say so rather than offer a switch that would silently do nothing, and the
 * playback choice has to disappear with it: nothing here can measure playback, so offering to
 * count it would trade real hours for a column of zeroes.
 *
 * Its own class because Robolectric puts one sandbox behind an SDK level, and the sibling class
 * runs on 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.M])
class SettingsScreenLegacyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val settings get() = app.container.settings

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Granted so the assertions can only be about the platform version — on the Play build a
        // denied permission would withhold detailed tracking for the other reason.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        app.container.database.deviceDao().deleteAll()
        settings.setDetailedTracking(false)
        settings.setPlaybackOnly(false)
    }

    private fun show() {
        val viewModel = YlihViewModel(app)
        compose.setContent {
            YlihTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    contentPadding = PaddingValues(),
                    onLanguageChanged = {},
                )
            }
        }
        settle("the screen to compose") { nodeCount(text(R.string.settings_about)) > 0 }
    }

    /** Writes finish on DataStore's own threads, so real time has to pass as well as frames. */
    private fun settle(what: String, until: () -> Boolean) {
        repeat(500) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun text(id: Int): String = app.getString(id)

    private fun nodeCount(value: String): Int =
        compose.onAllNodesWithText(value).fetchSemanticsNodes().size

    @Test
    fun `without notification channels the screen says so instead of offering a dead switch`() {
        show()

        // The note itself is worded for the Android 14 Bluetooth case, which is the only way the
        // Play build can get here; on this floor the reason is the platform version instead.
        assertEquals(1, nodeCount(text(R.string.settings_detailed_unavailable)))
        compose.onNode(
            isToggleable() and hasAnyAncestor(hasText(text(R.string.settings_detailed_title))),
        ).assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun `and the playback choice is not offered at all`() {
        show()

        assertEquals(0, nodeCount(text(R.string.settings_playback_only_title)))
    }
}
