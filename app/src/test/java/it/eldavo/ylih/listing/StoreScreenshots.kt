package it.eldavo.ylih.listing

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.YlihNavHost
import it.eldavo.ylih.ui.theme.YlihTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Generates the phone screenshots for the Play Console listing. Run with:
 *
 *     ./gradlew :app:recordRoborazziPlayDebug
 *
 * Outside a record task Roborazzi's capture calls do nothing, so this costs one composition in the
 * ordinary unit-test run and produces no files.
 *
 * The screen is 1080x1920: Play wants phone screenshots at a 9:16 ratio, and a Pixel-shaped
 * 1080x2400 is taller than that.
 *
 * Every label is looked up as a resource rather than typed in, so one implementation serves every
 * language the app ships — see the subclasses at the bottom. The JVM default locale is set to
 * match the resource qualifier because `ui/Format.kt` puts each duration and date through
 * `Locale.getDefault()`, which no resource qualifier reaches.
 *
 * See docs/play-store.md for what happens to these files.
 */
abstract class StoreScreenshots(
    private val locale: Locale,
    /** Subdirectory under the Roborazzi output dir; one per Play listing language. */
    private val outputDir: String,
) {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var app: YlihApp

    @Before
    fun setUp() {
        Locale.setDefault(locale)
        app = ApplicationProvider.getApplicationContext()

        // Ungranted Bluetooth makes the Play build render a red "detailed tracking unavailable"
        // block in Settings — a true state, but not the one to put in front of a reviewer.
        shadowOf(app as Application).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        runBlocking {
            app.container.database.deviceDao().deleteAll()
            DemoData.seed(app.container.database, System.currentTimeMillis())
            // Wired headphones and the playback share only exist in detailed mode, and the demo
            // data has both, so the settings shot has to agree with the stats shot.
            app.container.settings.setDetailedTracking(true)
            // Otherwise every shot is taken through the first-run welcome dialog.
            app.container.settings.setOnboardingDone(true)
        }
    }

    @Test
    fun `01 devices`() {
        showApp()
        awaitAnchor()
        capture("01-devices.png")
    }

    @Test
    fun `02 stats`() {
        showApp()
        awaitAnchor()
        compose.onNodeWithText(string(R.string.nav_stats)).performClick()
        awaitText(string(R.string.stats_title))
        capture("02-stats.png")
    }

    @Test
    fun `03 pair detail`() {
        showApp()
        awaitAnchor()
        // Summaries sort open sessions first, so the first match is the live generation-2 pair.
        compose.onAllNodesWithText(ANCHOR)[0].performClick()
        awaitText(string(R.string.pair_lifetime))
        capture("03-pair-detail.png")
    }

    @Test
    fun `04 settings`() {
        showApp()
        awaitAnchor()
        compose.onNodeWithText(string(R.string.nav_settings)).performClick()
        awaitText(string(R.string.settings_detailed_title))
        capture("04-settings.png")
    }

    @Test
    // A "+" qualifier merges with the class's rather than replacing it, so this stays language
    // and size agnostic and works for every subclass.
    @Config(qualifiers = "+night")
    fun `05 devices dark`() {
        showApp()
        awaitAnchor()
        capture("05-devices-dark.png")
    }

    private fun showApp() {
        compose.setContent {
            // Deliberately the app's normal theme, dynamic colours and all: on Robolectric those
            // resolve to the AOSP default palette, which is coherent and is what a phone on a
            // stock wallpaper shows.
            YlihTheme {
                YlihNavHost()
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("$outputDir/$name")

    private fun string(id: Int): String = app.getString(id)

    /** A seeded device name, so it reads the same in every language. */
    private fun awaitAnchor() = awaitText(ANCHOR)

    /** Room emits its first query result off the main thread; nothing renders until it lands. */
    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val ANCHOR = "Sony WH-1000XM4"
    }
}

/*
 * One concrete class per Play listing language, generated from the language list — the resource
 * qualifier picks the translation, the language tag localises what `ui/Format.kt` puts through
 * `Locale.getDefault()`, and the last argument names the output directory, which must match the
 * `fastlane/metadata/android/` directory for the same listing.
 *
 * Three of the qualifiers use Android's legacy language codes: `in` for Indonesian, `iw` for
 * Hebrew. The resource directories are named the same way, so they agree; the JVM tags next to
 * them are the modern ones, which is what `Locale.forLanguageTag` expects.
 */

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "en-rUS-w360dp-h640dp-xxhdpi",
)
class EnglishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("en-US"), "en-US")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "ar-rEG-w360dp-h640dp-xxhdpi",
)
class ArabicStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("ar-EG"), "ar")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "cs-rCZ-w360dp-h640dp-xxhdpi",
)
class CzechStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("cs-CZ"), "cs-CZ")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "da-rDK-w360dp-h640dp-xxhdpi",
)
class DanishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("da-DK"), "da-DK")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "de-rDE-w360dp-h640dp-xxhdpi",
)
class GermanStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("de-DE"), "de-DE")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "el-rGR-w360dp-h640dp-xxhdpi",
)
class GreekStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("el-GR"), "el-GR")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "es-rES-w360dp-h640dp-xxhdpi",
)
class SpanishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("es-ES"), "es-ES")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "fi-rFI-w360dp-h640dp-xxhdpi",
)
class FinnishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("fi-FI"), "fi-FI")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi",
)
class FrenchStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("fr-FR"), "fr-FR")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "hi-rIN-w360dp-h640dp-xxhdpi",
)
class HindiStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("hi-IN"), "hi-IN")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "hu-rHU-w360dp-h640dp-xxhdpi",
)
class HungarianStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("hu-HU"), "hu-HU")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "in-rID-w360dp-h640dp-xxhdpi",
)
class IndonesianStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("id-ID"), "id")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "it-rIT-w360dp-h640dp-xxhdpi",
)
class ItalianStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("it-IT"), "it-IT")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "iw-rIL-w360dp-h640dp-xxhdpi",
)
class HebrewStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("he-IL"), "iw-IL")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "ja-rJP-w360dp-h640dp-xxhdpi",
)
class JapaneseStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("ja-JP"), "ja-JP")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "ko-rKR-w360dp-h640dp-xxhdpi",
)
class KoreanStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("ko-KR"), "ko-KR")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "nb-rNO-w360dp-h640dp-xxhdpi",
)
class NorwegianBokmalStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("nb-NO"), "no-NO")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "nl-rNL-w360dp-h640dp-xxhdpi",
)
class DutchStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("nl-NL"), "nl-NL")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "pl-rPL-w360dp-h640dp-xxhdpi",
)
class PolishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("pl-PL"), "pl-PL")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "pt-rBR-w360dp-h640dp-xxhdpi",
)
class BrazilianPortugueseStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("pt-BR"), "pt-BR")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "ro-rRO-w360dp-h640dp-xxhdpi",
)
class RomanianStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("ro-RO"), "ro")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "ru-rRU-w360dp-h640dp-xxhdpi",
)
class RussianStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("ru-RU"), "ru-RU")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "sv-rSE-w360dp-h640dp-xxhdpi",
)
class SwedishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("sv-SE"), "sv-SE")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "th-rTH-w360dp-h640dp-xxhdpi",
)
class ThaiStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("th-TH"), "th")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "tr-rTR-w360dp-h640dp-xxhdpi",
)
class TurkishStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("tr-TR"), "tr-TR")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "uk-rUA-w360dp-h640dp-xxhdpi",
)
class UkrainianStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("uk-UA"), "uk")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "vi-rVN-w360dp-h640dp-xxhdpi",
)
class VietnameseStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("vi-VN"), "vi")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "zh-rCN-w360dp-h640dp-xxhdpi",
)
class SimplifiedChineseStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("zh-CN"), "zh-CN")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    qualifiers = "zh-rTW-w360dp-h640dp-xxhdpi",
)
class TraditionalChineseStoreScreenshots : StoreScreenshots(Locale.forLanguageTag("zh-TW"), "zh-TW")
