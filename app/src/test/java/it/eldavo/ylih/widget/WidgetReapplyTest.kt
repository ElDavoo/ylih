@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package it.eldavo.ylih.widget

import android.app.Application
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.stats.Counting
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * That a second update lands on a widget the launcher already has.
 *
 * A launcher does not re-inflate one: `AppWidgetHostView` recycles the view and *reapplies* the new
 * `RemoteViews` onto it, and Glance gives [LifetimeContent] the same root layout id whatever it
 * holds, so a reapply is always what happens. Two compositions therefore have to agree on the shape
 * of the tree — a tree that changed shape lands one view's action on another, throws part-way, and
 * leaves the launcher showing the half-updated view it already had.
 *
 * Composing either state on its own always looks perfectly correct, so a *pair* of compositions is
 * the only thing that can see this at all. [WidgetChronometerTest] pins the same invariant for the
 * live timer, which is the shape change that moved on every connect and disconnect.
 *
 * Here it is the row count, which moves when a pair is retired or deleted. It used to throw
 * whenever the rows were still stretching: a stretched list carries no trailing spacer, so it had
 * exactly one child per row and nothing to take a lost row's place. Where losing a row happened to
 * turn stretching *off* the spacer appeared as the row vanished, the child count did not change and
 * the update survived — by accident, which is why this sweeps rather than picking a size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetReapplyTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `a list that loses a pair redraws at every height the provider allows`() {
        // The provider's own declared range, which is what a launcher will let someone drag to.
        for (height in 60..640 step 20) {
            for (pairs in 1..5) {
                val size = DpSize(cells(4), height.dp)
                val view = compose(pairs, size).apply(app, FrameLayout(app))
                // Retiring a pair is the one thing that takes a row away on its own.
                compose(pairs - 1, size).reapply(app, view)
            }
        }
    }

    @Test
    fun `a list that gains a pair redraws the same way`() {
        // A headset connecting for the first time is a new pair, and arrives from the background.
        for (height in 60..640 step 20) {
            for (pairs in 0..4) {
                val size = DpSize(cells(4), height.dp)
                val view = compose(pairs, size).apply(app, FrameLayout(app))
                compose(pairs + 1, size).reapply(app, view)
            }
        }
    }

    private fun compose(pairs: Int, size: DpSize) = runBlocking {
        GlanceRemoteViews().compose(context = app, size = size) {
            LifetimeContent(app, data(pairs))
        }.remoteViews
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
}
