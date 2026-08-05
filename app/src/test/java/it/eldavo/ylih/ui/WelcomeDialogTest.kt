package it.eldavo.ylih.ui

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The welcome is shown once and never again, which is exactly the kind of thing that can stop
 * working without anyone noticing. What it says on the way through matters as much: the Bluetooth
 * page is the only place the app ever explains why it is asking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WelcomeDialogTest {

    // The android rule rather than the plain one: the Bluetooth page raises a real permission
    // request, and answering it needs the activity's result registry.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Finishing the run syncs, which reaches WorkManager; its androidx.startup initializer
        // never runs here.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun `first run explains the app, then asks, then stays gone`() {
        runBlocking { app.container.settings.setOnboardingDone(false) }
        denyBluetooth()
        compose.setContent { YlihTheme { YlihNavHost() } }

        val intro = app.getString(R.string.welcome_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(intro) > 0 }

        // The intro does not finish the run any more — there is a page after it, so the button
        // that used to say "get started" has to say so.
        compose.onNodeWithText(app.getString(R.string.welcome_next)).performClick()

        val bluetooth = app.getString(R.string.welcome_bluetooth_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(bluetooth) > 0 }
        // Both outcomes are spelled out, which is the whole reason this page exists rather than
        // the system prompt arriving on its own.
        compose.onNodeWithText(app.getString(R.string.welcome_bluetooth_with)).assertExists()
        compose.onNodeWithText(app.getString(R.string.welcome_bluetooth_without)).assertExists()

        // Declining is a first-class answer: it finishes the run like any other.
        compose.onNodeWithText(app.getString(R.string.action_not_now)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(bluetooth) == 0 }

        assertTrue(runBlocking { app.container.settings.onboardingDoneNow() })
    }

    @Test
    fun `allowing raises the real prompt and finishes the run on the answer`() {
        runBlocking { app.container.settings.setOnboardingDone(false) }
        denyBluetooth()
        compose.setContent { YlihTheme { YlihNavHost() } }

        val intro = app.getString(R.string.welcome_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(intro) > 0 }
        compose.onNodeWithText(app.getString(R.string.welcome_next)).performClick()

        val bluetooth = app.getString(R.string.welcome_bluetooth_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(bluetooth) > 0 }
        compose.onNodeWithText(app.getString(R.string.welcome_allow)).performClick()

        // The page is what raises the prompt, so that the reason is the last thing read before
        // the choice — the point of giving it a page at all.
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertTrue(
            request.requestedPermissions.contains(Manifest.permission.BLUETOOTH_CONNECT),
        )

        // RequestPermission hands back a single boolean, unlike the activity's batch contract.
        compose.activity.activityResultRegistry.dispatchResult(request.requestCode, true)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(bluetooth) == 0 }
        assertTrue(runBlocking { app.container.settings.onboardingDoneNow() })
    }

    @Test
    fun `the bluetooth page is left out once it has nothing to ask for`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(
            listOf(WelcomePage.Kind.INTRO),
            welcomePages(app).map { it.kind },
        )
    }

    @Test
    fun `notifications are never a first-run page`() {
        denyBluetooth()

        // They are asked for by the detailed-tracking switch instead — see SettingsScreen. A
        // page here would be asking every install for something most of them never use.
        assertEquals(
            listOf(WelcomePage.Kind.INTRO, WelcomePage.Kind.BLUETOOTH),
            welcomePages(app).map { it.kind },
        )
    }

    private fun denyBluetooth() =
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    private fun nodeCount(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size
}
