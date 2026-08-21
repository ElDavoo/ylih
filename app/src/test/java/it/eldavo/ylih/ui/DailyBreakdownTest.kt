package it.eldavo.ylih.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.ui.theme.YlihTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The day list is what the chart above it cannot be: a bar is a proportion, and "how long did I
 * listen yesterday" is a question about a figure. So what is pinned here is the two things a
 * reader takes from it — which day a row is, and that the bar beside the figure means something.
 *
 * Native graphics because the bars are real fills rather than semantics: nothing in the tree says
 * how wide one is, and a day that was listened to and a day that was not are the same node with a
 * different number in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class DailyBreakdownTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val today: LocalDate = LocalDate.of(2026, 3, 28)

    private fun series(vararg hours: Long): List<Pair<LocalDate, Long>> =
        hours.mapIndexed { index, h ->
            today.minusDays((hours.size - 1 - index).toLong()) to h * hour
        }

    @Test
    fun `the list runs newest first, which is the end of the series a reader wants`() {
        val days = dailyBreakdown(series(1, 2, 3))

        assertEquals(listOf(today, today.minusDays(1), today.minusDays(2)), days.map { it.first })
        assertEquals(3 * hour, days.first().second)
    }

    /**
     * The old end only. An install a fortnight old would otherwise open on sixteen rows of nothing,
     * which reads as history that was lost — the one thing this app must never look like it has
     * done — while a zero between two days that were listened to is a real answer.
     */
    @Test
    fun `empty days before the first one recorded are dropped, and the ones after are not`() {
        val days = dailyBreakdown(series(0, 0, 3, 0, 2))

        assertEquals(3, days.size)
        assertEquals(listOf(2 * hour, 0L, 3 * hour), days.map { it.second })
    }

    /** Today counts as a day even before anything has been played on it. */
    @Test
    fun `a silent today is still listed`() {
        val days = dailyBreakdown(series(4, 0))

        assertEquals(listOf(today, today.minusDays(1)), days.map { it.first })
        assertEquals(0L, days.first().second)
    }

    @Test
    fun `a window with nothing in it lists nothing at all`() {
        assertTrue(dailyBreakdown(series(0, 0, 0)).isEmpty())
    }

    private fun show(vararg rows: Pair<LocalDate, Long>): Color {
        // A Color is a value class, so this cannot be `lateinit`. It is read out of the theme
        // rather than written as a literal, so the assertion cannot drift from what is drawn.
        var primary = Color.Unspecified
        compose.setContent {
            YlihTheme {
                primary = MaterialTheme.colorScheme.primary
                Column {
                    rows.forEach { (date, ms) ->
                        DailyBreakdownRow(date = date, ms = ms, maxMs = 4 * hour, today = today)
                    }
                }
            }
        }
        compose.waitForIdle()
        return primary
    }

    private fun text(id: Int): String = app.getString(id)

    @Test
    fun `the two days anyone asks about are named rather than dated`() {
        show(today to hour, today.minusDays(1) to hour, today.minusDays(2) to hour)

        compose.onNodeWithText(text(R.string.stats_today), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.stats_yesterday), useUnmergedTree = true).assertExists()
        // Everything older carries its weekday as well as its date: a run of dates is read for its
        // weekly shape, and a date on its own does not say whether it was a working day.
        val older = today.minusDays(2)
        compose.onNodeWithText(
            "${formatWeekday(older)} · ${formatDayLabel(older)}",
            useUnmergedTree = true,
        ).assertExists()
    }

    /**
     * The day and the figure are one thing to hear. Unmerged they arrive as "3 h" and then the day
     * it belongs to, which is the wrong way round — the same reason `StatTile` merges.
     */
    @Test
    fun `a row reads to a screen reader as one figure, day first`() {
        show(today.minusDays(1) to 3 * hour)

        compose.onNodeWithContentDescription(
            "${text(R.string.stats_yesterday)}: ${formatHours(3 * hour)}",
        ).assertExists()
    }

    @Test
    fun `a day that was listened to draws a bar and a silent one draws none`() {
        val primary = show(today to 4 * hour, today.minusDays(1) to 0L)

        assertTrue(
            "the busiest day fills its track",
            primaryPixels(text(R.string.stats_today), primary) > 0,
        )
        assertEquals(
            "a day with nothing on it is a track and no fill",
            0,
            primaryPixels(text(R.string.stats_yesterday), primary),
        )
    }

    /**
     * Twenty minutes against a ten-hour day is a third of a pixel, and a bar that rounds away says
     * "nothing" about a day that was not nothing.
     */
    @Test
    fun `a day too short to draw is still drawn`() {
        val primary = show(today to 60_000L)

        assertTrue(primaryPixels(text(R.string.stats_today), primary) > 0)
    }

    /** How much of one row is painted in the bar colour, the row found by what it says. */
    private fun primaryPixels(day: String, primary: Color): Int {
        val pixels = compose.onNodeWithContentDescription(day, substring = true)
            .captureToImage()
            .toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] == primary) count++
            }
        }
        return count
    }
}
