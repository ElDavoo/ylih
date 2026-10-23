package it.eldavo.ylih.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.lifecycle.Lifecycle
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.BuildConfig
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AutoBackupError
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.SettingEntity
import it.eldavo.ylih.data.SettingsStore
import it.eldavo.ylih.export.FakeDocumentsProvider
import it.eldavo.ylih.tracking.MediaSessions
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.PlaybackInfoBuilder

/**
 * Settings holds the two irreversible actions — switching tracking modes and replacing the
 * whole database from a file — so the key assertions are that switches write through and an
 * import is never one tap away.
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
        settings.setAgentAccess(false)
        // Left over, this would compose the whole screen in whatever language the last test
        // picked — see the note on awaitDetailedTracking for why it's reset here.
        settings.setLanguage(AppLocale.SYSTEM)
        settings.setAutoBackupFolder(null)
        // The package rows have no clear of their own; an empty value is an empty list.
        db.settingsDao().put(SettingEntity(SettingsStore.EXCLUDED_APPS, ""))
        db.settingsDao().put(SettingEntity(SettingsStore.SEEN_APPS, ""))
        grantSessionAccess(false)
    }

    /** What the system's notification-access switch writes, read back by `MediaSessions`. */
    private fun grantSessionAccess(granted: Boolean) {
        Settings.Secure.putString(
            app.contentResolver,
            "enabled_notification_listeners",
            if (granted) MediaSessions.listener(app).flattenToString() else "",
        )
    }

    /** Another app's media session, saying it is playing. */
    private fun playing(packageName: String) {
        val controller = MediaController(app, MediaSession(app, packageName).sessionToken)
        shadowOf(controller).setPackageName(packageName)
        shadowOf(controller).setPlaybackInfo(
            PlaybackInfoBuilder.newBuilder()
                .setVolumeType(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL)
                .build(),
        )
        shadowOf(controller).setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build(),
        )
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(controller)
    }

    @get:Rule
    val tmp = TemporaryFolder()

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
     * Every write here goes through the view model and finishes on Room's own threads, so a
     * condition must be re-checked as real time passes, not just as the compose clock advances.
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

    /**
     * The picker intent, once the shadow has actually recorded the launch.
     *
     * `onClick` calls `launcher.launch(…)` outright, so this isn't a coroutine waiting to
     * resume — but `performClick()` returning doesn't guarantee the resulting work has drained
     * off Robolectric's main looper, which is paused and idled explicitly here. Read right after
     * the click, `nextStartedActivityForResult` is intermittently null, and the failure is a bare
     * `NullPointerException` wherever it's dereferenced, saying nothing about timing. It took the
     * `play` job down twice in one day while `classic` passed the same commit, and does not
     * reproduce locally.
     *
     * [settle] fixes it by idling that looper and letting real time pass, as the rest of this
     * file already does for every other wait here.
     *
     * `nextStartedActivityForResult` removes what it returns, so polling is safe: null until the
     * launch lands, then the value exactly once.
     */
    private fun awaitStartedForResult(what: String): Intent {
        var launched: Intent? = null
        settle(what) {
            launched = shadowOf(compose.activity).nextStartedActivityForResult?.intent
            launched != null
        }
        return launched!!
    }

    private fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun nodeCount(value: String, substring: Boolean = false): Int =
        compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().size

    /**
     * The setting must be reset *from inside the test body*: the write runs on the main thread
     * the Compose rule owns, and a blocking write from `@After` would wait on a thread only the
     * test body can free.
     *
     * This waits for the stored value alone. Where the switch is asserted too, use
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

    private fun awaitAgentAccess(on: Boolean) {
        settle("agent access to be $on") {
            runBlocking { settings.agentAccessNow() } == on
        }
    }

    /** Settings is a plain scrolling column, so the target brings itself into view. */
    private fun scrollTo(value: String) {
        // Static sections compose before Room answers, so `show()` returning says nothing about
        // a device row being on screen yet; scrolling to one that isn't there is what fails, and
        // only on a machine slow enough for the query to lose the race.
        settle("$value to be drawn") { nodeCount(value, substring = true) > 0 }
        compose.onAllNodesWithText(value, substring = true).onFirst().performScrollTo()
    }

    /**
     * `isToggleable()` alone is ambiguous — two switches live in the tracking section plus a
     * checkbox per known device — so the label tells them apart.
     *
     * The label sits *on* the toggle, not beside it: the whole row is the control, one click
     * target and one thing for a screen reader to announce with its state, the shape the
     * language dialog already used. The row merges its labels onto the toggleable node.
     */
    private fun toggleMatcher(label: String) = isToggleable() and hasText(label)

    private fun toggleBesides(label: String) = compose.onNode(toggleMatcher(label))

    /**
     * Waits for the *switch*, which the setting reaching its new value no longer implies.
     *
     * A write finishes in SQLite, and Room notifies the flow the screen collects only after
     * that, so `detailedTrackingNow()` can read the new value while the switch still draws the
     * old one. Under DataStore, the write and the emission were one in-memory update with no gap
     * to lose: asserting the switch right after [awaitDetailedTracking] was sound until settings
     * moved into the database. It raced on a loaded CI runner, on one of four variants, after
     * passing locally on all four.
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

        // The setting doesn't wait on the answer: the service runs and records either way, and
        // the permission only decides whether its notification draws. The switch is already on
        // behind the dialog.
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
        // grant must reach syncWithSystem or nothing appears until something else restarts it.
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
        // Saying so is only half of it: a switch left live beside the note would spring back on
        // every tap, reading as a bug rather than a build limit.
        val switch = toggleBesides(text(R.string.settings_detailed_title))
        if (Distribution.HAS_SPECIAL_USE_FGS) {
            switch.assertIsEnabled()
        } else {
            switch.assertIsOff().assertIsNotEnabled()
        }
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
    fun `the assistant switch writes the setting through, both ways`() {
        // Both directions, since they're not the same code path: turning it off must reach the
        // OS, because an app function left enabled is callable whatever this app's own table
        // says.
        show()

        val label = text(R.string.settings_agent_title)
        scrollTo(label)
        val switch = toggleBesides(label)
        // The default the app ships with, and the one the generated schema ships with too.
        switch.assertIsOff()
        switch.performClick()
        awaitAgentAccess(on = true)
        awaitToggleBesides(label, on = true)

        switch.performClick()
        awaitAgentAccess(on = false)
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

        // Nothing is written without the user picking a destination, so the launch is the assertion.
        val started = awaitStartedForResult("the export picker to be launched")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.action)
    }

    @Test
    fun `importing is confirmed before a decade of history is replaced`() {
        show()

        scrollTo(text(R.string.settings_import))
        compose.onNodeWithText(text(R.string.settings_import)).performClick()
        val started = awaitStartedForResult("the import picker to be launched")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.action)

        // Picking a file is not the same as agreeing to overwrite everything.
        shadowOf(compose.activity).receiveResult(
            started,
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
        val started = awaitStartedForResult("the import picker to be launched")
        shadowOf(compose.activity).receiveResult(
            started,
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
            // The button itself, not the section header: a row added higher up the screen would
            // push it out of the viewport, landing the tap on nothing.
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
        // matching what the platform actually says about this app.
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

    private fun awaitBackupFolder(folder: String?) {
        settle("the backup folder to be $folder") {
            runBlocking { settings.autoBackupNow().folder } == folder
        }
    }

    /** Switches automatic backups on through the picker, answering it with the fake folder. */
    private fun enableBackupsThroughPicker(): String {
        val label = text(R.string.settings_auto_backup_title)
        scrollTo(label)
        toggleBesides(label).performClick()
        val started = awaitStartedForResult("the folder picker to be launched")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.action)
        shadowOf(compose.activity).receiveResult(
            started,
            Activity.RESULT_OK,
            Intent().setData(FakeDocumentsProvider.treeUri),
        )
        val folder = FakeDocumentsProvider.treeUri.toString()
        awaitBackupFolder(folder)
        return label
    }

    @Test
    fun `switching automatic backups on is choosing a folder`() {
        FakeDocumentsProvider.install(tmp.root)
        show()

        val label = enableBackupsThroughPicker()

        awaitToggleBesides(label, on = true)
        // A grant the app will still hold after a reboot, or the first scheduled run would fail.
        assertTrue(
            app.contentResolver.persistedUriPermissions
                .any { it.uri == FakeDocumentsProvider.treeUri && it.isWritePermission },
        )
        settle("the folder to be named") { nodeCount(FakeDocumentsProvider.ROOT_NAME) > 0 }
        settle("the status to say nothing is written yet") {
            nodeCount(text(R.string.settings_auto_backup_none)) > 0
        }

        toggleBesides(label).performClick()
        awaitBackupFolder(null)
        awaitToggleBesides(label, on = false)
        settle("the grant to be released") { app.contentResolver.persistedUriPermissions.isEmpty() }
    }

    @Test
    fun `cancelling the folder picker leaves automatic backups off`() {
        show()

        val label = text(R.string.settings_auto_backup_title)
        scrollTo(label)
        toggleBesides(label).performClick()
        val started = awaitStartedForResult("the folder picker to be launched")
        shadowOf(compose.activity).receiveResult(started, Activity.RESULT_CANCELED, null)

        awaitToggleBesides(label, on = false)
        assertEquals(null, runBlocking { settings.autoBackupNow().folder })
    }

    @Test
    fun `the interval is picked from a list and written through`() {
        FakeDocumentsProvider.install(tmp.root)
        show()
        enableBackupsThroughPicker()

        scrollTo(text(R.string.settings_auto_backup_every))
        compose.onNodeWithText(text(R.string.settings_auto_backup_every)).performClick()
        settle("the interval dialog to open") {
            nodeCount(text(R.string.settings_auto_backup_monthly)) > 0
        }
        compose.onNodeWithText(text(R.string.settings_auto_backup_monthly)).performClick()

        settle("the interval to be a month") {
            runBlocking { settings.autoBackupNow().everyDays } == 30
        }
    }

    @Test
    fun `the interval dialog can be dismissed without changing anything`() {
        FakeDocumentsProvider.install(tmp.root)
        show()
        enableBackupsThroughPicker()

        scrollTo(text(R.string.settings_auto_backup_every))
        compose.onNodeWithText(text(R.string.settings_auto_backup_every)).performClick()
        settle("the interval dialog to open") {
            nodeCount(text(R.string.settings_auto_backup_daily)) > 0
        }
        compose.onNode(hasAnyAncestor(isDialog()) and hasText(text(R.string.action_cancel)))
            .performClick()

        settle("the dialog to close") { nodeCount(text(R.string.settings_auto_backup_daily)) == 0 }
        assertEquals(7, runBlocking { settings.autoBackupNow().everyDays })
    }

    @Test
    fun `tapping the folder row asks for a folder again`() {
        FakeDocumentsProvider.install(tmp.root)
        show()
        enableBackupsThroughPicker()

        // Not scrollTo(): "folder" is also a substring of the switch's own description. The wait is
        // still needed — the stored folder reaches the row only once the view model's flow emits,
        // and a loaded CI runner lost that race.
        val folder = text(R.string.settings_auto_backup_folder)
        settle("the folder row to be drawn") { nodeCount(folder) == 1 }
        compose.onNodeWithText(folder)
            .performScrollTo()
            .performClick()

        assertEquals(
            Intent.ACTION_OPEN_DOCUMENT_TREE,
            awaitStartedForResult("the folder picker to reopen").action,
        )
    }

    @Test
    fun `the status line says what the last run found`() {
        FakeDocumentsProvider.install(tmp.root)
        show()
        enableBackupsThroughPicker()

        runBlocking { settings.recordAutoBackup(1_700_000_000_000L, null) }
        settle("the last backup to be shown") {
            nodeCount(text(R.string.settings_auto_backup_last, formatDateTime(1_700_000_000_000L))) > 0
        }

        runBlocking { settings.recordAutoBackup(1_700_000_000_000L, AutoBackupError.WRITE_FAILED) }
        settle("the write failure to be shown") {
            nodeCount(text(R.string.settings_auto_backup_write_failed)) > 0
        }

        runBlocking { settings.recordAutoBackup(1_700_000_000_000L, AutoBackupError.ACCESS_LOST) }
        settle("the lost folder to be shown") {
            nodeCount(text(R.string.settings_auto_backup_access_lost)) > 0
        }
    }

    @Test
    fun `with detailed tracking off there is no app to leave out`() {
        // Only the service measures playback, so the list would change nothing.
        show()

        assertEquals(0, nodeCount(text(R.string.settings_apps_title)))
    }

    @Test
    fun `switching detailed tracking off leads to taking notification access back`() {
        // Leaving apps out needs the service, so with it off the grant does nothing — and only
        // the system screen can revoke it.
        grantSessionAccess(true)
        runBlocking { settings.setDetailedTracking(true) }
        show()

        val label = text(R.string.settings_detailed_title)
        awaitToggleBesides(label, on = true)
        toggleBesides(label).performClick()
        awaitDetailedTracking(on = false)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, started.action)
        assertEquals(
            MediaSessions.listener(app).flattenToString(),
            started.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
        )
    }

    @Test
    fun `switching detailed tracking off without the grant goes nowhere`() {
        runBlocking { settings.setDetailedTracking(true) }
        show()

        val label = text(R.string.settings_detailed_title)
        awaitToggleBesides(label, on = true)
        toggleBesides(label).performClick()
        awaitDetailedTracking(on = false)

        assertEquals("nothing to take back", null, shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a grant left behind by detailed tracking stays in sight until revoked`() {
        // Backing out of the system screen keeps the grant. Hidden with the rest, the switch
        // would leave it held for nothing with no way back to it.
        grantSessionAccess(true)
        runBlocking { settings.recordSeenApps(setOf("com.llamalab.automate")) }
        show()

        val label = text(R.string.settings_apps_title)
        scrollTo(label)
        awaitToggleBesides(label, on = true)
        assertEquals("nothing runs to act on the list", 0, nodeCount("com.llamalab.automate"))

        toggleBesides(label).performClick()
        assertEquals(
            Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
            shadowOf(app).nextStartedActivity.action,
        )
    }

    @Test
    fun `the apps switch stands for the system grant and opens its page`() {
        runBlocking { settings.setDetailedTracking(true) }
        show()

        val label = text(R.string.settings_apps_title)
        scrollTo(label)
        toggleBesides(label).assertIsOff().performClick()

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, started.action)
        assertEquals(
            "the page for this app's listener, not every app's",
            MediaSessions.listener(app).flattenToString(),
            started.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
        )
    }

    @Test
    fun `where the page of its own is missing the list of listeners opens instead`() {
        // Some builds have no per-listener page; low-RAM ones have neither, and must not crash.
        shadowOf(app).checkActivities(true)
        val listSettings = ComponentName("com.android.settings", "NotificationAccessSettings")
        shadowOf(app.packageManager).addActivityIfNotPresent(listSettings)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            listSettings,
            IntentFilter(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
        runBlocking { settings.setDetailedTracking(true) }
        show()

        val label = text(R.string.settings_apps_title)
        scrollTo(label)
        toggleBesides(label).performClick()

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun `with no page for the grant at all the tap opens nothing and nothing breaks`() {
        shadowOf(app).checkActivities(true)
        runBlocking { settings.setDetailedTracking(true) }
        show()

        val label = text(R.string.settings_apps_title)
        scrollTo(label)
        toggleBesides(label).performClick()

        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `an app seen playing can be left out, and let back in`() {
        grantSessionAccess(true)
        runBlocking {
            settings.setDetailedTracking(true)
            settings.recordSeenApps(setOf("com.llamalab.automate"))
        }
        show()

        // Not a launcher app this phone can see, so it goes by its package name.
        scrollTo("com.llamalab.automate")
        awaitToggleBesides(text(R.string.settings_apps_title), on = true)
        toggleBesides("com.llamalab.automate").assertIsOn().performClick()
        settle("the app to be left out") {
            runBlocking { settings.excludedApps.first() } == setOf("com.llamalab.automate")
        }
        awaitToggleBesides("com.llamalab.automate", on = false)

        toggleBesides("com.llamalab.automate").performClick()
        settle("the app to be let back in") {
            runBlocking { settings.excludedApps.first() }.isEmpty()
        }
    }

    @Test
    fun `an app is listed by the name it goes by`() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.spotify.music"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.spotify.music"
                    nonLocalizedLabel = "Spotify"
                }
            },
        )
        grantSessionAccess(true)
        runBlocking {
            settings.setDetailedTracking(true)
            settings.recordSeenApps(setOf("com.spotify.music"))
        }
        show()

        scrollTo("Spotify")
        assertEquals(0, nodeCount("com.spotify.music"))
    }

    @Test
    fun `without the grant the list stays hidden, since nothing could act on it`() {
        runBlocking {
            settings.setDetailedTracking(true)
            settings.recordSeenApps(setOf("com.llamalab.automate"))
        }
        show()

        scrollTo(text(R.string.settings_apps_title))
        assertEquals(0, nodeCount("com.llamalab.automate"))
    }

    @Test
    fun `coming back from the system screen shows the grant and what is playing`() {
        runBlocking { settings.setDetailedTracking(true) }
        show()
        scrollTo(text(R.string.settings_apps_title))
        awaitToggleBesides(text(R.string.settings_apps_title), on = false)

        // What the user does on the system screen: switch access on, with Automate running.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        grantSessionAccess(true)
        playing("com.llamalab.automate")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        awaitToggleBesides(text(R.string.settings_apps_title), on = true)
        settle("the app playing to be listed") { nodeCount("com.llamalab.automate") > 0 }
        assertEquals(setOf("com.llamalab.automate"), runBlocking { settings.seenApps.first() })
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `below android 11 the switch opens the list of every listener`() {
        // Android 10 has no page per listener, and names apps through the pre-13 label call.
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.llamalab.automate"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.llamalab.automate"
                    nonLocalizedLabel = "Automate"
                }
            },
        )
        grantSessionAccess(true)
        runBlocking {
            settings.setDetailedTracking(true)
            settings.recordSeenApps(setOf("com.llamalab.automate"))
        }
        show()

        scrollTo("Automate")
        val label = text(R.string.settings_apps_title)
        toggleBesides(label).performClick()

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }
}
