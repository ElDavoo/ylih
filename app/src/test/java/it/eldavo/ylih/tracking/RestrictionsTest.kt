package it.eldavo.ylih.tracking

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the app is allowed to tell the user about hibernation. It changes nothing the app does —
 * it exists so a total that stopped growing has an explanation on the screen instead of looking
 * like a bug in the counting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class RestrictionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `hibernation is reported as whatever the platform admits to`() = runTest {
        shadowOf(context.packageManager).setAutoRevokeWhitelisted(false)
        assertEquals(Hibernation.ENABLED, Restrictions.hibernation(context))

        shadowOf(context.packageManager).setAutoRevokeWhitelisted(true)
        assertEquals(Hibernation.DISABLED, Restrictions.hibernation(context))
    }

    @Test
    fun `there is a system screen to send the user to`() {
        // The app cannot exempt itself from hibernation — only this screen can — so a null here
        // would leave the prompt with nothing to offer.
        assertNotNull(Restrictions.settingsIntent(context))
    }
}
