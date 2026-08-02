package it.eldavo.ylih.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.MainActivity
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.ui.formatHours
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * What each widget puts on screen at each of the sizes it declares.
 *
 * The size buckets are the whole of a Glance widget's layout logic — nothing else decides how many
 * rows a lifetime widget draws or whether the chart gets its labels — so every bucket is composed
 * here and asked what it rendered. The figures themselves are [WidgetDataTest]'s problem; this
 * builds [WidgetData] by hand so that a change in the arithmetic cannot quietly rewrite what these
 * assert.
 *
 * Labels are looked up as resources rather than typed in, the way the listing generators do it, so
 * these keep passing when the English wording changes and fail when a string is dropped.
 */
@RunWith(RobolectricTestRunner::class)
// The chart widget really rasterises its bars — Glance has no Canvas — and Robolectric's legacy
// graphics hand back a null Bitmap for that, which surfaces as an NPE inside Compose.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `the tall lifetime widget lists three pairs under a heading`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(2)))
        provideComposable { LifetimeContent(context, data()) }

        onNode(hasText(context.getString(R.string.nav_headphones))).assertExists()
        onNode(hasText("Galaxy Buds3 Pro")).assertExists()
        onNode(hasText("Sony WH-1000XM4")).assertExists()
        onNode(hasText("Sennheiser HD 25")).assertExists()
        onNode(hasText("AirPods Pro")).assertDoesNotExist()
    }

    @Test
    fun `the short lifetime widget is only the pair being worn`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(1)))
        provideComposable { LifetimeContent(context, data()) }

        // A single row has no space to spend on a heading, and the connected pair sorts first.
        onNode(hasText(context.getString(R.string.nav_headphones))).assertDoesNotExist()
        onNode(hasText("Galaxy Buds3 Pro")).assertExists()
        onNode(hasText("Sony WH-1000XM4")).assertDoesNotExist()
    }

    @Test
    fun `the tallest lifetime widget has room for the whole collection`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(4)))
        provideComposable { LifetimeContent(context, data()) }

        onNode(hasText("AirPods Pro")).assertExists()
    }

    @Test
    fun `the lifetime widget says when its hours are playback time`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(2)))
        provideComposable { LifetimeContent(context, data(counting = Counting.PLAYBACK)) }

        // The heading is the only line spare, so the note takes its place rather than pushing a
        // row out. Silently halved lifetime totals are the one thing this app must never show.
        onNode(hasText(context.getString(R.string.stats_playback_only_note))).assertExists()
        onNode(hasText(context.getString(R.string.nav_headphones))).assertDoesNotExist()
    }

    @Test
    fun `a lifetime widget with nothing to show says so`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(2)))
        provideComposable { LifetimeContent(context, data(rows = emptyList())) }

        onNode(hasText(context.getString(R.string.devices_empty_title))).assertExists()
    }

    @Test
    fun `each row opens its own pair`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(2)))
        provideComposable { LifetimeContent(context, data()) }

        // The tap is on the row, so each is found by the label it contains rather than by being it.
        onNode(hasStartActivityClickAction(intentFor(7L)) and rowFor("Galaxy Buds3 Pro")).assertExists()
        onNode(hasStartActivityClickAction(intentFor(8L)) and rowFor("Sony WH-1000XM4")).assertExists()
        // Two rows, two intents, and they have to differ somewhere a PendingIntent actually
        // compares — which is never the extras. Were the data URIs equal, this would find four
        // rows carrying pair 7's intent and every one of them would open pair 7.
        onAllNodes(hasStartActivityClickAction(intentFor(7L))).assertCountEquals(1)
    }

    @Test
    fun `a lifetime widget between two cell heights lists what fits, not what a bucket says`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            // Half a cell above the two-row size. Under the old fixed set of layouts this drew the
            // shorter of the two and left the rest of the widget empty.
            setAppWidgetSize(DpSize(cells(4), 145.dp))
            provideComposable { LifetimeContent(context, data()) }

            onNode(hasText("Sennheiser HD 25")).assertExists()
            onNode(hasText("AirPods Pro")).assertExists()
        }

    @Test
    fun `the wide activity widget shows all four windows`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(1)))
        provideComposable { ActivityContent(context, data()) }

        onNode(hasText(context.getString(R.string.stats_title))).assertExists()
        onNode(hasText(context.getString(R.string.stats_today))).assertExists()
        onNode(hasText(context.getString(R.string.stats_last_7))).assertExists()
        onNode(hasText(context.getString(R.string.stats_last_30))).assertExists()
        onNode(hasText(formatHours(900 * hour))).assertExists()
    }

    @Test
    fun `the narrow activity widget drops the two it cannot fit`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(2), cells(1)))
        provideComposable { ActivityContent(context, data()) }

        onNode(hasText(context.getString(R.string.stats_title))).assertExists()
        onNode(hasText(context.getString(R.string.stats_today))).assertExists()
        // Squeezing four figures into half a row would leave captions nobody can read.
        onNode(hasText(context.getString(R.string.stats_last_7))).assertDoesNotExist()
        onNode(hasText(context.getString(R.string.stats_last_30))).assertDoesNotExist()
    }

    @Test
    fun `a tall activity widget keeps all four windows by using a second line`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            // Two cells across is half of what the one-line layout needs for four figures; the
            // second line is what lets this shape carry them at all, and until the provider
            // allowed a vertical drag it was a shape nobody could ask for.
            setAppWidgetSize(DpSize(cells(2), cells(2)))
            provideComposable { ActivityContent(context, data()) }

            onNode(hasText(context.getString(R.string.stats_title))).assertExists()
            onNode(hasText(context.getString(R.string.stats_today))).assertExists()
            onNode(hasText(context.getString(R.string.stats_last_7))).assertExists()
            onNode(hasText(context.getString(R.string.stats_last_30))).assertExists()
        }

    @Test
    fun `the narrowest activity widget is the lifetime total alone`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(1), cells(1)))
        provideComposable { ActivityContent(context, data()) }

        onNode(hasText(context.getString(R.string.stats_title))).assertExists()
        onNode(hasText(context.getString(R.string.stats_today))).assertDoesNotExist()
    }

    @Test
    fun `the activity widget says when the total is playback time`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(1)))
        provideComposable { ActivityContent(context, data(counting = Counting.PLAYBACK)) }

        // A lifetime total that has quietly halved must never appear without saying why.
        onNode(hasText(context.getString(R.string.stats_playing))).assertExists()
        onNode(hasText(context.getString(R.string.stats_title))).assertDoesNotExist()
    }

    @Test
    fun `the tall chart widget is labelled and the short one is bars`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(cells(4), cells(2)))
            provideComposable { ChartContent(context, data()) }

            onNode(hasText(context.getString(R.string.stats_daily_hours_30))).assertExists()
            onNode(hasText(context.getString(R.string.chart_max, formatHours(3 * hour)))).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(cells(4), cells(1)))
            provideComposable { ChartContent(context, data()) }

            onNode(hasText(context.getString(R.string.stats_daily_hours_30))).assertDoesNotExist()
            onNode(hasText(context.getString(R.string.chart_max, formatHours(3 * hour)))).assertDoesNotExist()
        }
    }

    @Test
    fun `a narrow chart keeps the dates and drops the tallest-day figure`() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(cells(2), cells(2)))
            provideComposable { ChartContent(context, data()) }

            // Three captions on one line need about four cells. The two dates are the pair that
            // says what the bars span, so they are the pair that stays.
            onNode(hasText(context.getString(R.string.chart_max, formatHours(3 * hour))))
                .assertDoesNotExist()
            onNode(hasText(context.getString(R.string.stats_daily_hours_30))).assertExists()
        }

    @Test
    fun `a chart with no history still draws`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(cells(4), cells(2)))
        // What a fresh install has. The day labels come off the ends of the series, so an empty
        // one is where this reaches for a date that is not there.
        provideComposable { ChartContent(context, data(series = emptyList())) }

        onNode(hasText(context.getString(R.string.stats_daily_hours_30))).assertExists()
    }

    /** The clickable row wrapping a pair's label, rather than the label itself. */
    private fun rowFor(label: String) = hasAnyDescendant(hasText(label))

    /** The same intent [openPair] builds, to compare the row's click action against. */
    private fun intentFor(pairId: Long) =
        Intent(context, MainActivity::class.java).apply {
            data = "ylih://widget/$pairId".toUri()
            putExtra(MainActivity.EXTRA_PAIR_ID, pairId)
        }

    private fun data(
        rows: List<WidgetRow> = listOf(
            WidgetRow(pairId = 7L, label = "Galaxy Buds3 Pro", lifetimeMs = 40 * hour, openSince = now - hour),
            WidgetRow(pairId = 8L, label = "Sony WH-1000XM4", lifetimeMs = 300 * hour, openSince = null),
            WidgetRow(pairId = 9L, label = "Sennheiser HD 25", lifetimeMs = 20 * hour, openSince = null),
            WidgetRow(pairId = 10L, label = "AirPods Pro", lifetimeMs = 5 * hour, openSince = null),
        ),
        counting: Counting = Counting.CONNECTED,
        // A rising month, so the tallest bar and therefore the "max" label are predictable.
        series: List<Pair<LocalDate, Long>> = (0 until WIDGET_DAYS).map {
            LocalDate.of(2026, 3, 1).plusDays(it.toLong()) to (3 * hour * it / (WIDGET_DAYS - 1))
        },
    ) = WidgetData(
        rows = rows,
        totalMs = 900 * hour,
        todayMs = 2 * hour,
        weekMs = 14 * hour,
        monthMs = 60 * hour,
        series = series,
        counting = counting,
        now = now,
    )
}
