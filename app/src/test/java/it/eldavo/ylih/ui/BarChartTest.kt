package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The chart is a hand-drawn Canvas rather than a charting dependency, so nothing but a real
 * draw exercises it — hence native graphics and a capture. What is asserted is the part a
 * reader relies on: the span it covers and the scale its tallest bar means.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class BarChartTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val day: LocalDate = LocalDate.of(2026, 3, 28)

    private fun show(series: List<Pair<LocalDate, Long>>) {
        compose.setContent { YlihTheme { DailyBarChart(series) } }
        // Only a capture makes the Canvas block run; composing it draws nothing.
        compose.onRoot().captureToImage()
    }

    @Test
    fun `the chart is labelled with its span and its tallest day`() {
        show((0..6).map { day.plusDays(it.toLong()) to it * hour })

        compose.onNodeWithText(formatDayLabel(day)).assertExists()
        compose.onNodeWithText(formatDayLabel(day.plusDays(6))).assertExists()
        compose.onNodeWithText(app.getString(R.string.chart_max, formatHours(6 * hour)))
            .assertExists()
    }

    @Test
    fun `a day with no listening at all still leaves its track drawn`() {
        // Nothing to scale against, so the maximum is clamped rather than divided by zero.
        show(listOf(day to 0L, day.plusDays(1) to 0L))

        compose.onNodeWithText(app.getString(R.string.chart_max, formatHours(1L))).assertExists()
    }

    @Test
    fun `an empty series draws an empty chart rather than crashing`() {
        show(emptyList())

        val image = compose.onRoot().captureToImage()
        assertTrue("the chart keeps its height so the page does not jump", image.height > 120)
        // There are no days to label, but the scale line is still drawn.
        compose.onNodeWithText(app.getString(R.string.chart_max, formatHours(1L))).assertExists()
    }
}
