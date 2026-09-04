package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 *
 * The axis labels are drawn but deliberately hidden from the semantics tree — a Canvas has nothing
 * inside it to describe itself, so the whole chart carries one description and three loose
 * fragments after it would only repeat the same figures out of order. Hence `useUnmergedTree` for
 * what is on screen, and a separate assertion for what a screen reader is told.
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

    private fun show(series: List<Pair<LocalDate, Long>>, label: String? = null) {
        compose.setContent { YlihTheme { DailyBarChart(series, label = label) } }
        // Only a capture makes the Canvas block run; composing it draws nothing.
        compose.onRoot().captureToImage()
    }

    @Test
    fun `the chart is labelled with its span and its tallest day`() {
        show((0..6).map { day.plusDays(it.toLong()) to it * hour })

        compose.onNodeWithText(formatDayLabel(day), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(formatDayLabel(day.plusDays(6)), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText(
            app.getString(R.string.chart_max, formatHours(6 * hour)),
            useUnmergedTree = true,
        ).assertExists()
    }

    /**
     * The app's whole visualisation used to be an empty leaf to TalkBack: a bare Canvas with no
     * semantics at all, and three tiny axis labels beside it. This is the line it reads now.
     */
    @Test
    fun `the chart tells a screen reader what it is a chart of`() {
        show((0..6).map { day.plusDays(it.toLong()) to it * hour }, label = "daily hours")

        compose.onNodeWithContentDescription("daily hours", substring = true)
            .assertExists()
        compose.onNodeWithContentDescription(formatDayLabel(day), substring = true)
            .assertExists()
        compose.onNodeWithContentDescription(
            app.getString(R.string.chart_max, formatHours(6 * hour)),
            substring = true,
        ).assertExists()
        compose.onNodeWithContentDescription(formatHours(21 * hour), substring = true)
            .assertExists()
    }

    @Test
    fun `a day with no listening at all still leaves its track drawn`() {
        // Nothing to scale against, so the maximum is clamped rather than divided by zero.
        show(listOf(day to 0L, day.plusDays(1) to 0L))

        compose.onNodeWithText(
            app.getString(R.string.chart_max, formatHours(1L)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun showCycles(values: List<Long>) {
        compose.setContent {
            YlihTheme {
                CycleBarChart(
                    values = values,
                    label = "charge cycles",
                    firstLabel = "cycle 1",
                    lastLabel = "cycle ${values.size}",
                )
            }
        }
        compose.onRoot().captureToImage()
    }

    /**
     * The cycle chart draws the same bars against a different axis — an ordinal rather than a date
     * — because the only thing it is read for is whether the bars get shorter.
     */
    @Test
    fun `the cycle chart is labelled with its span and its best cycle`() {
        showCycles(listOf(10 * hour, 8 * hour, 6 * hour))

        compose.onNodeWithText("cycle 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("cycle 3", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(
            app.getString(R.string.chart_max, formatHours(10 * hour)),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `the cycle chart tells a screen reader what it is a chart of`() {
        showCycles(listOf(10 * hour, 6 * hour))

        compose.onNodeWithContentDescription("charge cycles", substring = true).assertExists()
        compose.onNodeWithContentDescription("cycle 1 – cycle 2", substring = true).assertExists()
    }

    /**
     * A pair worn daily for a decade is some three thousand charge cycles, and three thousand bars
     * on a phone is a bar narrower than a pixel drawn three thousand times a frame. Averaging runs
     * of them keeps the only thing the chart is read for — whether the bars are getting shorter.
     */
    @Test
    fun `more cycles than there are bars are averaged into the bars there are`() {
        assertEquals(listOf(1L, 2L, 3L), bucketedBars(listOf(1L, 2L, 3L), max = 4))
        assertEquals(listOf(1L, 2L, 3L), bucketedBars(listOf(1L, 2L, 3L), max = 3))

        // Six into four does not divide, so runs of two make three buckets rather than five.
        assertEquals(listOf(15L, 35L, 55L), bucketedBars(listOf(10L, 20L, 30L, 40L, 50L, 60L), 4))

        // The last run may be short and is still the average of what is in it.
        assertEquals(listOf(15L, 30L), bucketedBars(listOf(10L, 20L, 30L), max = 2))

        val many = (1..3_000).map { it.toLong() }
        val bars = bucketedBars(many, max = 40)
        assertTrue("never more bars than asked for: ${bars.size}", bars.size <= 40)
        assertTrue("and the shape survives", bars.first() < bars.last())
    }

    @Test
    fun `an empty series draws an empty chart rather than crashing`() {
        show(emptyList())

        val image = compose.onRoot().captureToImage()
        assertTrue("the chart keeps its height so the page does not jump", image.height > 120)
        // There are no days to label, but the scale line is still drawn.
        compose.onNodeWithText(
            app.getString(R.string.chart_max, formatHours(1L)),
            useUnmergedTree = true,
        ).assertExists()
    }
}
