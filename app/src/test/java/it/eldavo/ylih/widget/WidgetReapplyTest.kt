@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package it.eldavo.ylih.widget

import android.app.Application
import android.os.Build
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceComposable
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * That a second update lands on a widget the launcher already has.
 *
 * A launcher does not always re-inflate one: where the root layout id is unchanged
 * `AppWidgetHostView` recycles the view it has and *reapplies* the new `RemoteViews` onto it. Two
 * compositions that reach that path therefore have to agree on the shape of the tree — one that
 * changed shape lands a view's action on another, throws part-way, and leaves the launcher showing
 * the half-updated view it already had. Composing either state on its own always looks perfectly
 * correct, so a *pair* of compositions is the only thing that can see this at all.
 * [WidgetChronometerTest] pins the same invariant for the live timer.
 *
 * A widget's shape moves along two axes and they turn out to behave differently.
 *
 * The **data** axis reaches a reapply, and so is the one that can fail. Rows appear and vanish as
 * pairs are retired or first connect, which used to throw whenever the rows were still stretching:
 * a stretched list carries no trailing spacer, so it had exactly one child per row and nothing to
 * take a lost row's place. Where losing a row happened to turn stretching *off* the spacer appeared
 * as the row vanished, the child count did not change, and the update survived by accident — which
 * is why the first two tests sweep rather than picking a size.
 *
 * The **size** axis is a drag of the resize handles, and it is safe by construction, which is worth
 * writing down because it does not look it. Every widget here lays itself out differently across a
 * size: the lifetime rows gain their timers at three cells across, the chart its labels at two cells
 * down. That is the same kind of shape change, and dropping a `Chronometer` out of a recycled tree
 * would be the worst version of it, since the system goes on ticking one it has been left holding.
 * It never happens, because Glance keys a layout id on the whole node tree — a shape that moved
 * anywhere gives the *root* a different id, and the host then inflates afresh rather than reapplying.
 * The three sweeps below are what say so, across every distinct layout the providers' declared
 * ranges can produce, and what would catch the day a change let two differently-shaped trees share
 * a root id. That is the only way this becomes a bug.
 */
@RunWith(RobolectricTestRunner::class)
// ChartContent really rasterises its bars — Glance has no Canvas — and Robolectric's legacy
// graphics hand back a null Bitmap for that, which surfaces as an NPE from inside Compose.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetReapplyTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `a list that loses a pair redraws at every height the provider allows`() {
        var reapplied = 0
        // The provider's own declared range, which is what a launcher will let someone drag to.
        for (height in 60..640 step 20) {
            for (pairs in 1..5) {
                val size = DpSize(cells(4), height.dp)
                // Retiring a pair is the one thing that takes a row away on its own.
                if (redraws(size, pairs, pairs - 1)) reapplied++
            }
        }
        assertReachedAReapply(reapplied)
    }

    @Test
    fun `a list that gains a pair redraws the same way`() {
        var reapplied = 0
        // A headset connecting for the first time is a new pair, and arrives from the background.
        for (height in 60..640 step 20) {
            for (pairs in 0..4) {
                val size = DpSize(cells(4), height.dp)
                if (redraws(size, pairs, pairs + 1)) reapplied++
            }
        }
        assertReachedAReapply(reapplied)
    }

    @Test
    fun `the lifetime widget redraws when it is dragged to any other size`() {
        assertRedrawsAcrossSizes(R.xml.widget_lifetime_info, ::lifetimeShape) {
            LifetimeContent(app, data(PAIRS))
        }
    }

    @Test
    fun `the activity widget redraws when it is dragged to any other size`() {
        assertRedrawsAcrossSizes(R.xml.widget_activity_info, ::activityShape) {
            ActivityContent(app, data(PAIRS))
        }
    }

    @Test
    fun `the chart widget redraws when it is dragged to any other size`() {
        assertRedrawsAcrossSizes(R.xml.widget_chart_info, ::chartShape) {
            ChartContent(app, data(PAIRS))
        }
    }

    /**
     * One widget's two successive compositions at one size, the second delivered onto the first.
     *
     * @return whether the launcher recycled the view rather than inflating a new one.
     */
    private fun redraws(size: DpSize, beforePairs: Int, afterPairs: Int): Boolean {
        var reapplied = false
        onOneWidget { compose ->
            reapplied = deliver(
                before = compose(size) { LifetimeContent(app, data(beforePairs)) },
                after = compose(size) { LifetimeContent(app, data(afterPairs)) },
            )
        }
        return reapplied
    }

    /**
     * Puts each size on screen in turn and delivers every other size onto it.
     *
     * Both directions, because they are not the same event: gaining a child is a redraw that finds
     * a view where it expected a different one, losing a child is a redraw that leaves the old view
     * behind — and with a `Chronometer` the system goes on ticking what it was left.
     */
    private fun assertRedrawsAcrossSizes(
        providerInfo: Int,
        shapeOf: (width: Float, height: Float) -> Any,
        content: @Composable @GlanceComposable () -> Unit,
    ) {
        val sizes = representativeSizes(providerInfo, shapeOf)
        for (from in sizes) {
            for (to in sizes) {
                if (from == to) continue
                // Named, because the sizes are derived rather than written down: a bare
                // ActionException says a tree changed shape but not which drag did it.
                val drag = "$from -> $to"
                runCatching {
                    onOneWidget { compose -> deliver(compose(from, content), compose(to, content)) }
                }.onFailure { throw AssertionError("dragging $drag does not redraw", it) }
            }
        }
    }

    /**
     * That the sweep just run actually exercised the thing this file is named for.
     *
     * Not every row change reaches a reapply, and the ones that do not are right not to: losing the
     * last pair swaps the list for a line of text, and a row count that also flips the rows between
     * stretching and settled changes their layout too — both are a different tree from the root
     * down, so the launcher inflates afresh and there is no recycled view to get wrong. What matters
     * is that the middle of the range, where only the row count moves, still lands on a recycled
     * one. Without this, a change that gave every row count its own layout would turn the whole
     * sweep into a run of inflations that assert nothing, and it would stay green.
     */
    private fun assertReachedAReapply(reapplied: Int) = assertTrue(
        "no redraw in this sweep reached a reapply, so none of it tested one",
        reapplied > 0,
    )

    /**
     * Hands [after] to a host already showing [before], the way `AppWidgetHostView` does it.
     *
     * The host re-inflates when the root layout id has changed and reapplies onto the recycled view
     * when it has not, so the rule has to be here too. Reapplying unconditionally reports a failure
     * on every redraw the launcher would have answered by inflating afresh, which is not a failure
     * at all; only ever inflating would assert nothing, since the reapply is where the hazard lives.
     *
     * @return whether this pair exercised a reapply, so a sweep can prove it exercised some.
     */
    private fun deliver(before: RemoteViews, after: RemoteViews): Boolean {
        val view = before.apply(app, FrameLayout(app))
        if (before.layoutId != after.layoutId) {
            // Inflated rather than skipped: it is what the launcher does with this pair, and a
            // tree that cannot be inflated at all is worth failing on wherever it turns up.
            after.apply(app, FrameLayout(app))
            return false
        }
        after.reapply(app, view)
        return true
    }

    /**
     * One size per distinct layout the provider's declared range can produce.
     *
     * The range is thousands of size pairs and the sweep above is quadratic in them, but nearly all
     * of them draw the same tree — and what a redraw trips over is the tree *changing shape*. So one
     * representative of each shape is exactly the set worth crossing, and reading the shapes off the
     * layout functions themselves means a new threshold in one of them lands here without anyone
     * having to remember to add a size.
     */
    private fun representativeSizes(
        providerInfo: Int,
        shapeOf: (width: Float, height: Float) -> Any,
    ): List<DpSize> {
        val seen = mutableSetOf<Any>()
        val sizes = mutableListOf<DpSize>()
        ProviderSizes.sweep(app, providerInfo) { width, height ->
            if (seen.add(shapeOf(width, height))) sizes += DpSize(width.dp, height.dp)
        }
        return sizes
    }

    /**
     * What [LifetimeContent] lays out: the header, the rows it shows, how those share the height,
     * and whether each one carries a live timer.
     */
    private fun lifetimeShape(width: Float, height: Float): Any {
        val header = height >= cells(2).value
        val rows = minOf(lifetimeRows(height, header), PAIRS)
        return listOf(header, rows, lifetimeStretches(height, header, rows), width >= cells(3).value)
    }

    /** [ActivityContent] draws one line or two, and as many figures across as fit. */
    private fun activityShape(width: Float, height: Float): Any = activityGrid(width, height)

    /** [ChartContent]'s title and day labels, and the tallest-day figure between them. */
    private fun chartShape(width: Float, height: Float): Any =
        listOf(height >= cells(2).value, width >= cells(4).value)

    /**
     * Runs [block] against the compositions of a *single* widget, which is the only arrangement
     * that models a reapply at all.
     *
     * Glance decides which generated layout a node gets by looking the node's shape up in a
     * [androidx.glance.appwidget.LayoutConfiguration] and handing out the next free index when it
     * finds nothing — and a real widget keeps one of those per widget id, across compositions, for
     * exactly this reason: it is what makes a second composition reuse the first's layout ids where
     * the shape is unchanged. `GlanceRemoteViews` holds that map per instance, so composing the
     * before and the after from two instances restarts the numbering, hands two differently-shaped
     * trees the same ids, and reports a collision no launcher would ever see.
     */
    private fun onOneWidget(
        block: (compose: (DpSize, @Composable @GlanceComposable () -> Unit) -> RemoteViews) -> Unit,
    ) {
        val widget = GlanceRemoteViews()
        block { size, content ->
            runBlocking { widget.compose(context = app, size = size, content = content).remoteViews }
        }
    }

    /** [pairs] active pairs, the first of them connected, the way the widget sorts them. */
    private fun data(pairs: Int) = WidgetData(
        rows = (1..pairs).map {
            WidgetRow(
                pairId = it.toLong(),
                label = "pair $it",
                lifetimeMs = 40 * hour,
                openSince = if (it == 1) now - hour else null,
            )
        },
        totalMs = 40 * hour,
        todayMs = hour,
        weekMs = 7 * hour,
        monthMs = 30 * hour,
        series = listOf(LocalDate.of(2026, 3, 1) to hour),
        counting = Counting.CONNECTED,
        now = now,
    )

    private companion object {
        /** Enough pairs for the rows to be a shape of their own, few enough to fit the short sizes. */
        const val PAIRS = 3
    }
}
