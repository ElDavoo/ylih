package it.eldavo.ylih.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The static palette is only ever chosen with dynamic colour switched off, which in practice is
 * the Play listing screenshots alone — so nothing else in the suite would notice it rotting.
 * It once set `primary`/`secondary`/`tertiary` and nothing else, which left every container role
 * at Material's default purple: roles come in sets, and that is what these assertions pin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ThemeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `with dynamic colour off both schemes are the app's own, containers included`() {
        lateinit var light: ColorScheme
        lateinit var dark: ColorScheme
        compose.setContent {
            YlihTheme(darkTheme = false, dynamicColor = false) {
                light = MaterialTheme.colorScheme
            }
            YlihTheme(darkTheme = true, dynamicColor = false) {
                dark = MaterialTheme.colorScheme
            }
        }
        compose.waitForIdle()

        assertNotEquals("light and dark are different palettes", light.primary, dark.primary)
        for ((role, ours, material) in
            listOf(
                Triple("primary", light.primary, lightColorScheme().primary),
                Triple("primaryContainer", light.primaryContainer, lightColorScheme().primaryContainer),
                Triple("secondaryContainer", light.secondaryContainer, lightColorScheme().secondaryContainer),
                Triple("surfaceContainerHigh", light.surfaceContainerHigh, lightColorScheme().surfaceContainerHigh),
                Triple("dark primaryContainer", dark.primaryContainer, darkColorScheme().primaryContainer),
            )
        ) {
            assertNotEquals("$role is still Material's default", material, ours)
        }
    }
}
