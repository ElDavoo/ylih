package it.eldavo.ylih

import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Smoke test: the whole startup path — Application container, Room opening the real database,
 * permission launchers, Compose composition — has to survive without an emulator in the loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MainActivityTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // onStart() syncs, which reaches WorkManager; its initializer never runs here.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun `application exposes a usable container`() {
        assertNotNull(app.container.repository)
        assertNotNull(app.container.database.openHelper.writableDatabase)
    }

    @Test
    fun `main activity reaches resumed state`() {
        runBlocking { app.container.settings.setOnboardingDone(true) }

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNotNull(controller.get())
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `below android 13 the activity is attached with the language the app was told to use`() {
        runBlocking {
            app.container.settings.setOnboardingDone(true)
            app.container.settings.setLanguage("it")
        }

        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                assertEquals(
                    "it",
                    controller.get().resources.configuration.locales[0].language,
                )
            }
        } finally {
            runBlocking { app.container.settings.setLanguage(AppLocale.SYSTEM) }
            Locale.setDefault(Locale.US)
        }
    }

    /**
     * A widget row's tap arriving at an activity that is already up.
     *
     * `singleTop` means the launcher hands it to [MainActivity.onNewIntent] rather than stacking a
     * second copy, and the pair id reaches the NavHost through a conflated channel. The flow over
     * that channel is a field rather than something `setContent` builds, because `receiveAsFlow()`
     * allocates a new object per call and the `LaunchedEffect` keyed on it would restart — losing
     * whatever a `trySend` had just conflated into it.
     */
    @Test
    fun `a widget tap arriving at an open activity is carried through`() {
        runBlocking { app.container.settings.setOnboardingDone(true) }

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()

            controller.newIntent(
                Intent(activity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAIR_ID, 42L),
            )
            shadowOf(Looper.getMainLooper()).idle()

            // `setIntent` is the load-bearing half: `onCreate` acts on the attached intent only
            // when there is no saved state, so an intent left unreplaced would be re-read as the
            // launch intent after a rotation and drag the user back out of wherever they had gone.
            assertEquals(
                42L,
                activity.intent.getLongExtra(MainActivity.EXTRA_PAIR_ID, -1L),
            )
            assertFalse("the tap must reuse the activity, not finish it", activity.isFinishing)
        }
        // What the id then does is YlihNavHostTest's: it drives the same flow into the NavHost.
    }

    @Test
    fun `a first run leaves the asking to the welcome`() {
        runBlocking { app.container.settings.setOnboardingDone(false) }

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(
                "asking before explaining would be the first thing a new install ever said",
                shadowOf(controller.get()).lastRequestedPermission,
            )

            // And it stays that way when the welcome finishes. The welcome's own Bluetooth page
            // raised that prompt, with a reason attached; a second one from here would land
            // behind the answer the user had just given to the first.
            runBlocking { app.container.settings.setOnboardingDone(true) }
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(
                "the welcome asks for Bluetooth itself, so this would be the second prompt",
                shadowOf(controller.get()).lastRequestedPermission,
            )
        }
    }

    @Test
    fun `a later launch asks again for what is still missing`() {
        // The case this covers is an install upgraded from a version whose welcome never had a
        // Bluetooth page, plus anyone who declined once and might not now.
        runBlocking { app.container.settings.setOnboardingDone(true) }

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            settle("the permission request") {
                shadowOf(controller.get()).lastRequestedPermission != null
            }
            val requested = shadowOf(controller.get()).lastRequestedPermission.requestedPermissions
            assertTrue(requested.contains(android.Manifest.permission.BLUETOOTH_CONNECT))
            // Notifications belong to the detailed-tracking switch now. Asking here would hit
            // every install, including the ones that never turn detailed tracking on.
            assertFalse(
                "notifications are asked for where they start to matter, not on launch",
                requested.contains(android.Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    @Test
    fun `a refused permission leaves the app tracking what it still can`() {
        runBlocking { app.container.settings.setOnboardingDone(true) }

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            settle("the permission request") {
                shadowOf(activity).lastRequestedPermission != null
            }
            val request = shadowOf(activity).lastRequestedPermission

            // The registry is handed the parsed result: the contract's own output is a map.
            activity.activityResultRegistry.dispatchResult(
                request.requestCode,
                request.requestedPermissions.associateWith { false },
            )
            shadowOf(Looper.getMainLooper()).idle()

            // Denied Bluetooth is a supported state, not a crash: the sync runs regardless.
            assertNotNull(controller.get())
        }
    }

    /** The onboarding flag is read from DataStore, which answers on its own threads. */
    private fun settle(what: String, until: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
