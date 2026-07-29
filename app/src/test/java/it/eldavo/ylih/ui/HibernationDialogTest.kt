package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one restriction the app cannot work around asks for help exactly once, so every way of
 * getting that wrong is silent: asked twice is nagging, asked when already exempt is a prompt
 * spent on nothing, and never asked at all is a total that quietly stops growing months later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class HibernationDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val settings get() = app.container.settings

    private fun show(hibernating: Boolean, asked: Boolean = false) {
        shadowOf(app.packageManager).setAutoRevokeWhitelisted(!hibernating)
        runBlocking {
            // Strictly after the welcome: the gate reads both, and onboarding still open would
            // hide the prompt for a reason that has nothing to do with what is under test.
            settings.setOnboardingDone(true)
            settings.setHibernationAsked(asked)
        }
        compose.setContent { YlihTheme { YlihNavHost() } }
    }

    @Test
    fun `a phone that hibernates apps is asked, once, and the answer sticks`() {
        show(hibernating = true)

        val title = app.getString(R.string.hibernation_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(title) > 0 }
        compose.onNodeWithText(app.getString(R.string.action_not_now)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(title) == 0 }

        assertTrue(runBlocking { settings.hibernationAsked.first() })
    }

    @Test
    fun `saying yes remembers the answer as well as opening the screen that can act on it`() {
        show(hibernating = true)

        val title = app.getString(R.string.hibernation_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(title) > 0 }
        compose.onNodeWithText(app.getString(R.string.settings_hibernation_button)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(title) == 0 }

        // Recorded on the way out either way: the app never learns what the user did on the
        // system screen, so a prompt that only counted "not now" would come back forever.
        assertTrue(runBlocking { settings.hibernationAsked.first() })
        assertNotNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `an app already exempt is not asked for something it has`() {
        show(hibernating = false)

        // There is no negative to wait for, so wait for the app itself and then look.
        val tab = app.getString(R.string.nav_headphones)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(tab) > 0 }
        assertEquals(0, nodeCount(app.getString(R.string.hibernation_title)))
        // And the question stays unanswered, so a later revocation can still raise it.
        assertEquals(false, runBlocking { settings.hibernationAsked.first() })
    }

    @Test
    fun `a phone that has already been asked is left alone`() {
        show(hibernating = true, asked = true)

        val tab = app.getString(R.string.nav_headphones)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(tab) > 0 }
        assertEquals(0, nodeCount(app.getString(R.string.hibernation_title)))
    }

    private fun nodeCount(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size
}
