package it.eldavo.ylih.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.activity.ComponentActivity
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.BuildConfig
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings is where the two irreversible things live — turning tracking modes on and replacing
 * the whole database from a file — so the interesting assertions are that the switches write
 * through and that an import is never one tap away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val db get() = app.container.database
    private val settings get() = app.container.settings

    @Before
    fun setUp() = runBlocking {
        // The switch reaches TrackingController, which schedules or cancels the heartbeat.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // The Play build has no `specialUse` fallback, so detailed tracking there is only
        // offered once Bluetooth has been granted.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        db.deviceDao().deleteAll()
        settings.setDetailedTracking(false)
        settings.setPlaybackOnly(false)
        // Left over, this would compose the whole screen in whichever language the last test
        // picked — see the note on awaitDetailedTracking about why it is put back from here.
        settings.setLanguage(AppLocale.SYSTEM)
    }

    private fun seedDevice(name: String = "ACCENTUM Plus", ignored: Boolean = false): Long =
        runBlocking {
            db.deviceDao().insert(
                DeviceEntity(
                    deviceKey = "bt:5E:C2",
                    kind = DeviceKind.BLUETOOTH,
                    defaultName = name,
                    firstSeenAt = 1_700_000_000_000L,
                    ignored = ignored,
                ),
            )
        }

    private fun show(onLanguageChanged: () -> Unit = {}) {
        val viewModel = YlihViewModel(app)
        compose.setContent {
            YlihTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    contentPadding = PaddingValues(),
                    onLanguageChanged = onLanguageChanged,
                )
            }
        }
        settle("the screen to compose") { nodeCount(text(R.string.settings_about)) > 0 }
    }

    /**
     * Every write on this screen goes through the view model and finishes on Room's own threads,
     * so a condition has to be re-checked as real time passes rather than only as the compose
     * clock is advanced.
     */
    private fun settle(what: String, until: () -> Boolean) {
        repeat(500) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun nodeCount(value: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().size

    /**
     * The setting has to be left as it was found *from inside the test body*: the write runs on
     * the main thread the Compose rule owns, and a blocking write from `@After` would wait on a
     * thread only the test body can still free.
     *
     * This waits for the stored value alone. Where the switch is asserted too, that needs
     * [awaitToggleBesides] — reaching the store no longer means reaching the screen.
     */
    private fun awaitDetailedTracking(on: Boolean) {
        settle("detailed tracking to be $on") {
            runBlocking { settings.detailedTrackingNow() } == on
        }
    }

    private fun awaitPlaybackOnly(on: Boolean) {
        settle("playback-only stats to be $on") {
            runBlocking { settings.playbackOnlyNow() } == on
        }
    }

    /** Settings is a plain scrolling column, so the target brings itself into view. */
    private fun scrollTo(value: String) {
        // The static sections compose before Room has answered, so `show()` returning says
        // nothing about a device row being on the screen yet; scrolling to one that is not there
        // is what fails, and only on a machine slow enough for the query to lose the race.
        settle("$value to be drawn") { nodeCount(value, substring = true) > 0 }
        compose.onAllNodesWithText(value, substring = true).onFirst().performScrollTo()
    }

    /**
     * `isToggleable()` alone is ambiguous — there are two switches in the tracking section and a
     * checkbox per known device. Each row merges its own labels, so the label beside a control is
     * what tells them apart, and it keeps working when another row is added above.
     */
    private fun toggleMatcher(label: String) = isToggleable() and hasAnyAncestor(hasText(label))

    private fun toggleBesides(label: String) = compose.onNode(toggleMatcher(label))

    /**
     * Waits for the *switch*, which the setting having reached its new value no longer implies.
     *
     * A write finishes in SQLite and Room notifies the flow the screen is collecting only after
     * it, so `detailedTrackingNow()` can already read the new value while the switch still draws
     * the old one. Under DataStore the write and the emission were one in-memory update and there
     * was no gap to lose: asserting the switch straight after [awaitDetailedTracking] was sound
     * and stopped being sound when the settings moved into the database. It raced on a loaded CI
     * runner, on one of the four variants, having passed locally on all four.
     */
    private fun awaitToggleBesides(label: String, on: Boolean) {
        settle("the switch beside $label to read ${if (on) "on" else "off"}") {
            compose.onAllNodes(toggleMatcher(label) and (if (on) isOn() else isOff()))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `the tracking switch writes the setting through, both ways`() {
        show()

        val label = text(R.string.settings_detailed_title)
        val switch = toggleBesides(label)
        switch.assertIsOff()
        switch.performClick()
        awaitDetailedTracking(on = true)
        awaitToggleBesides(label, on = true)

        switch.performClick()
        awaitDetailedTracking(on = false)
        awaitToggleBesides(label, on = false)
    }

    @Test
    fun `turning detailed tracking on explains the notification it is about to need`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()

        val label = text(R.string.settings_detailed_title)
        toggleBesides(label).performClick()
        awaitDetailedTracking(on = true)

        // The setting does not wait on the answer: the service runs and records either way, and
        // the permission only decides whether its notification is drawn. So the switch is already
        // on behind the dialog.
        awaitToggleBesides(label, on = true)
        settle("the notification explainer") { nodeCount(text(R.string.welcome_notifications_title)) > 0 }
        compose.onNodeWithText(text(R.string.welcome_notifications_without)).assertExists()

        compose.onNodeWithText(text(R.string.action_not_now)).performClick()
        settle("the explainer to go") { nodeCount(text(R.string.welcome_notifications_title)) == 0 }
        awaitDetailedTracking(on = true)
    }

    @Test
    fun `allowing the notification raises the prompt and repairs the running service`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()

        toggleBesides(text(R.string.settings_detailed_title)).performClick()
        awaitDetailedTracking(on = true)
        settle("the notification explainer") { nodeCount(text(R.string.welcome_notifications_title)) > 0 }

        compose.onNodeWithText(text(R.string.welcome_allow)).performClick()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertTrue(
            request.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS),
        )

        // The service is already up and posted its notification with nowhere to put it, so the
        // grant has to reach syncWithSystem or nothing would appear until something else
        // restarted it.
        compose.activity.activityResultRegistry.dispatchResult(request.requestCode, true)
        settle("the explainer to go") { nodeCount(text(R.string.welcome_notifications_title)) == 0 }
        awaitDetailedTracking(on = true)
    }

    @Test
    fun `an install that already has the permission is not asked again`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        show()

        toggleBesides(text(R.string.settings_detailed_title)).performClick()
        awaitDetailedTracking(on = true)

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "nothing to ask for, so nothing to interrupt with",
            0,
            nodeCount(text(R.string.welcome_notifications_title)),
        )
    }

    @Test
    fun `the row around the switch is the same switch`() {
        show()

        // The whole row is clickable so the setting is not a small target.
        compose.onNodeWithText(text(R.string.settings_detailed_title)).performClick()
        awaitDetailedTracking(on = true)

        compose.onNodeWithText(text(R.string.settings_detailed_title)).performClick()
        awaitDetailedTracking(on = false)
    }

    @Test
    fun `a build that cannot do detailed tracking says so instead of offering it`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        show()

        assertEquals(
            "the Play build has no specialUse type, so denied Bluetooth means no service",
            if (Distribution.HAS_SPECIAL_USE_FGS) 0 else 1,
            nodeCount(text(R.string.settings_detailed_unavailable)),
        )
    }

    @Test
    fun `the playback switch writes the setting through, both ways`() {
        show()

        val label = text(R.string.settings_playback_only_title)
        val switch = toggleBesides(label)
        switch.assertIsOff()
        switch.performClick()
        awaitPlaybackOnly(on = true)
        awaitToggleBesides(label, on = true)

        switch.performClick()
        awaitPlaybackOnly(on = false)
        awaitToggleBesides(label, on = false)
    }

    @Test
    fun `a build that cannot measure playback does not offer to count it`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        show()

        assertEquals(
            "only the foreground service ever measures playback, so without it the switch " +
                "would offer a column of zeroes",
            if (Distribution.HAS_SPECIAL_USE_FGS) 1 else 0,
            nodeCount(text(R.string.settings_playback_only_title)),
        )
    }

    @Test
    fun `each known device can be excluded from tracking`() {
        val deviceId = seedDevice()
        show()

        scrollTo("ACCENTUM Plus")
        compose.onNodeWithText("ACCENTUM Plus").performClick()

        settle("the device to be ignored") {
            runBlocking { db.deviceDao().byId(deviceId)!!.ignored }
        }
        // Unticking is what "ignored" looks like on screen, and it has to survive a recomposition.
        compose.onAllNodesWithText("ACCENTUM Plus").onFirst().assertExists()
    }

    @Test
    fun `the checkbox beside a device is the same control as its row`() {
        val deviceId = seedDevice(ignored = true)
        show()

        scrollTo("ACCENTUM Plus")
        toggleBesides("ACCENTUM Plus").performClick()

        settle("the device to be tracked again") {
            runBlocking { !db.deviceDao().byId(deviceId)!!.ignored }
        }
    }

    @Test
    fun `with nothing ever connected there is no device section at all`() {
        show()

        assertEquals(0, nodeCount(text(R.string.settings_devices)))
    }

    @Test
    fun `exporting asks the system where to put the file`() {
        show()

        scrollTo(text(R.string.settings_export))
        compose.onNodeWithText(text(R.string.settings_export)).performClick()

        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("nothing is written without the user picking a destination", started)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
    }

    @Test
    fun `importing is confirmed before a decade of history is replaced`() {
        show()

        scrollTo(text(R.string.settings_import))
        compose.onNodeWithText(text(R.string.settings_import)).performClick()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)

        // Picking a file is not the same as agreeing to overwrite everything.
        shadowOf(compose.activity).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().setData("content://test/backup.json".toUri()),
        )

        settle("the confirmation to appear") {
            nodeCount(text(R.string.settings_import_confirm_body)) > 0
        }
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        settle("the confirmation to close") {
            nodeCount(text(R.string.settings_import_confirm_body)) == 0
        }
    }

    @Test
    fun `confirming an import that cannot be read leaves the history alone`() {
        seedDevice()
        show()

        scrollTo(text(R.string.settings_import))
        compose.onNodeWithText(text(R.string.settings_import)).performClick()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        shadowOf(compose.activity).receiveResult(
            started.intent,
            Activity.RESULT_OK,
            Intent().setData("content://test/missing.json".toUri()),
        )
        settle("the confirmation to appear") {
            nodeCount(text(R.string.settings_import_confirm_body)) > 0
        }

        // "import" also names the button that opened the picker, so aim inside the dialog.
        compose.onNode(hasAnyAncestor(isDialog()) and hasText(text(R.string.action_import)))
            .performClick()

        settle("the confirmation to close") {
            nodeCount(text(R.string.settings_import_confirm_body)) == 0
        }
        assertEquals(1, runBlocking { db.deviceDao().getAll().size })
    }

    @Test
    fun `the battery advice is a shortcut or a path, never nothing`() {
        show()

        if (Distribution.HAS_BATTERY_SHORTCUT) {
            // The button itself, not the section header above it: a row added higher up the
            // screen otherwise pushes it back out of the viewport and the tap lands on nothing.
            scrollTo(text(R.string.settings_battery_button))
            compose.onNodeWithText(text(R.string.settings_battery_button)).performClick()
            assertEquals(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                shadowOf(app).nextStartedActivity.action,
            )
        } else {
            // Play review dislikes the shortcut, so that build spells the path out instead.
            assertTrue(nodeCount(text(R.string.settings_battery_path)) > 0)
            assertEquals(0, nodeCount(text(R.string.settings_battery_button)))
        }
    }

    @Test
    fun `the restrictions section reports the hibernation state it found`() {
        shadowOf(app.packageManager).setAutoRevokeWhitelisted(false)
        show()

        // Read at compose time rather than typed in, so this fails if the reporting stops
        // reflecting what the platform actually says about this app.
        settle("the hibernation check to answer") {
            nodeCount(text(R.string.settings_hibernation_on)) > 0
        }
        assertEquals(0, nodeCount(text(R.string.settings_hibernation_off)))

        scrollTo(text(R.string.settings_hibernation_button))
        compose.onNodeWithText(text(R.string.settings_hibernation_button)).performClick()
        // The exemption is only the system's to grant, so the button has to leave the app.
        assertNotNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `below android 13 a language can be picked, and applying it restarts the activity`() {
        var restarts = 0
        show(onLanguageChanged = { restarts++ })

        scrollTo(text(R.string.settings_language))
        // The row shows what is in force, which with nothing stored is the system's own language.
        compose.onNodeWithText(text(R.string.settings_language_system)).performClick()
        settle("the picker to open") { nodeCount(text(R.string.action_cancel)) > 0 }

        // The first translation in the list, so it is on screen without scrolling the dialog.
        val tag = AppLocale.pickerTags(app).first()
        compose.onNode(hasAnyAncestor(isDialog()) and hasText(AppLocale.displayName(tag)))
            .performClick()

        settle("the language to be stored") { runBlocking { settings.languageNow() } == tag }
        // Restarting before the write lands would reattach the activity with the old language.
        settle("the activity to be restarted") { restarts == 1 }
    }

    @Test
    fun `from android 13 the app defers to the system language setting`() {
        show()

        // The platform owns per-app language there, and two answers to one question is worse
        // than one: the locale config already puts ylih in Settings > System > Languages.
        assertEquals(0, nodeCount(text(R.string.settings_language)))
        assertEquals(0, nodeCount(text(R.string.settings_language_system)))
    }

    @Test
    fun `the about line names the build that is actually installed`() {
        show()

        scrollTo(text(R.string.settings_about))
        compose.onNodeWithText(
            text(R.string.settings_about_body, BuildConfig.VERSION_NAME, Distribution.ID),
        ).assertExists()
        assertFalse(BuildConfig.VERSION_NAME.isEmpty())
    }
}
