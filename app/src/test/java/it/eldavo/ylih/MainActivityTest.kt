package it.eldavo.ylih

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test: the whole startup path — Application container, Room opening the real database,
 * permission launchers, Compose composition — has to survive without an emulator in the loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MainActivityTest {

    @Test
    fun `application exposes a usable container`() {
        val app = ApplicationProvider.getApplicationContext<YlihApp>()
        assertNotNull(app.container.repository)
        assertNotNull(app.container.database.openHelper.writableDatabase)
    }

    @Test
    fun `main activity reaches resumed state`() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertNotNull(controller.get())
        }
    }
}
