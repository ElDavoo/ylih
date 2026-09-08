package it.eldavo.ylih.widget

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one rule [refreshWidgets] has: it cannot throw.
 *
 * It's called from inside the write paths — the controller after every session change, the view
 * model after every edit — so anything it let escape would take a session write or a rename down
 * with it. Nothing here hosts a widget, the ordinary case on most phones and exactly the
 * situation `runCatching` exists for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetRefreshTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `refreshing survives having nowhere to refresh`() = runTest {
        refreshWidgets(app)
    }
}
