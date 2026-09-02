package it.eldavo.ylih.ui

import android.Manifest
import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings on a build that cannot run the foreground service at all.
 *
 * There is one route left to that state: on Android 14+ the `connectedDevice` service type
 * requires a Bluetooth permission, and the Play flavor declares no `specialUse` type to fall back
 * on — so a denied `BLUETOOTH_CONNECT` withholds detailed tracking there and nowhere else. The
 * classic build declares both types and is never blocked, which is why every assertion here is
 * guarded by [Distribution.HAS_SPECIAL_USE_FGS] rather than written twice.
 *
 * The screen must say so rather than offer a switch that would silently do nothing, and the
 * playback choice has to disappear with it: nothing here can measure playback, so offering to
 * count it would trade real hours for a column of zeroes.
 *
 * Its own class because Robolectric puts one sandbox behind an SDK level, and the sibling class
 * runs with the permission granted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SettingsScreenUnsupportedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val settings get() = app.container.settings

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Denied, which is the one route left to the unavailable state: on Android 14+ the
        // connectedDevice service type requires a Bluetooth permission, and the play flavor
        // declares no specialUse type to fall back on. The classic build is never blocked,
        // which is what the assumption in each test is about.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
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
    fun `without bluetooth access the screen says so instead of offering a dead switch`() {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
        show()

        assertEquals(1, nodeCount(text(R.string.settings_detailed_unavailable)))
        compose.onNode(
            isToggleable() and hasText(text(R.string.settings_detailed_title)),
        ).assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun `and the playback choice is not offered at all`() {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
        show()

        assertEquals(0, nodeCount(text(R.string.settings_playback_only_title)))
    }

    @Test
    fun `a build with the special-use type is never blocked by a denied permission`() {
        assumeTrue("classic declares specialUse", Distribution.HAS_SPECIAL_USE_FGS)
        show()

        assertEquals(0, nodeCount(text(R.string.settings_detailed_unavailable)))
        compose.onNode(
            isToggleable() and hasText(text(R.string.settings_detailed_title)),
        ).assertIsEnabled()
    }
}
