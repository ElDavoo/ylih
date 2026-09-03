package it.eldavo.ylih.widget

import android.content.Context
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.stats.Counting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The widgets below the API 31 floor, which is most of the range this app supports.
 *
 * `GlanceTheme` reads the platform's dynamic palette, and that palette does not exist before
 * Android 12 — so on everything from the Android 8 floor up to 11 the widgets fall back to the
 * app's own colours instead. That is a branch no amount of testing at API 34 reaches, and getting
 * it wrong is not a crash: it is a widget rendered in Material's default purple on most of the
 * installed base, which nobody testing on a recent phone would ever see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.O])
class WidgetsLegacyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `all three render on a phone with no dynamic colour`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(cells(4), cells(2)))
            provideComposable { LifetimeContent(context, data()) }
            onNode(hasText("Galaxy Buds3 Pro")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(cells(4), cells(1)))
            provideComposable { ActivityContent(context, data()) }
            onNode(hasText(context.getString(R.string.stats_today))).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(cells(4), cells(2)))
            provideComposable { ChartContent(context, data()) }
            onNode(hasText(context.getString(R.string.stats_daily_hours_30))).assertExists()
        }
    }

    @Test
    fun `a widget speaks the language the app was set to`() = runTest {
        // Android 13 gave the system a per-app language picker; below it the setting is the app's
        // own, and a context Glance hands in knows nothing about it. Forgetting to wrap it would
        // put the widget in the system language on every phone older than that.
        val app: YlihApp = ApplicationProvider.getApplicationContext()
        app.container.settings.setLanguage("it")
        try {
            val (localized, _) = widgetContentFlow(app).first()
            // Asserted as a resolved string rather than as a Locale, because a widget's whole
            // contact with the language is what it can look up through this context.
            assertEquals("cuffie", localized.getString(R.string.nav_headphones))
        } finally {
            app.container.settings.setLanguage(AppLocale.SYSTEM)
        }
    }

    private fun data(): WidgetData {
        val hour = 3_600_000L
        return WidgetData(
            rows = listOf(WidgetRow(7L, "Galaxy Buds3 Pro", 40 * hour, openSince = null)),
            totalMs = 900 * hour,
            todayMs = 2 * hour,
            weekMs = 14 * hour,
            monthMs = 60 * hour,
            series = (0 until WIDGET_DAYS).map {
                LocalDate.of(2026, 3, 1).plusDays(it.toLong()) to it * hour
            },
            counting = Counting.CONNECTED,
            now = 1_800_000_000_000L,
        )
    }
}
