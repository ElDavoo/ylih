package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import it.eldavo.ylih.data.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The kinds are what a pair's card and its page call the thing being worn, and the `when` that
     * maps them is the sort of list a new kind gets added to without a new string — which shows up
     * as two kinds sharing a name rather than as a crash.
     */
    @Test
    fun `every kind of output has a name of its own`() {
        lateinit var names: List<String>
        compose.setContent {
            names = DeviceKind.entries.map { it.displayName() }
        }
        compose.waitForIdle()

        assertEquals(DeviceKind.entries.size, names.toSet().size)
        assertTrue("a kind with no name would read as a blank separator", names.none { it.isBlank() })
    }
}
