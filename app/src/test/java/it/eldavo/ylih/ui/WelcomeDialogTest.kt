package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The welcome is shown once and never again, which is exactly the kind of thing that can stop
 * working without anyone noticing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WelcomeDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    @Test
    fun `first run explains the app and then stays gone`() {
        runBlocking { app.container.settings.setOnboardingDone(false) }
        compose.setContent { YlihTheme { YlihNavHost() } }

        val title = app.getString(R.string.welcome_title)
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(title) > 0 }
        compose.onNodeWithText(app.getString(R.string.welcome_start)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { nodeCount(title) == 0 }

        assertTrue(runBlocking { app.container.settings.onboardingDoneNow() })
    }

    private fun nodeCount(text: String): Int =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().size
}
