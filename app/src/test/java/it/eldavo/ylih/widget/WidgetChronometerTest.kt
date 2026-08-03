@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package it.eldavo.ylih.widget

import android.app.Application
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Whether the live timer is drawn at a given size.
 *
 * It arrives through `AndroidRemoteViews`, and nothing can see inside one of those — the Glance
 * node matchers [WidgetsTest] uses find a leaf with no text and no children. So this composes the
 * widget the way the launcher will, into a `RemoteViews`, and then inflates it and looks for the
 * Chronometer itself. It is the only assertion in the suite that reaches past Glance's own tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetChronometerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `a widget with room for the phrase gets a live timer on the connected pair`() {
        assertTrue(ticking(DpSize(cells(4), cells(2))))
    }

    @Test
    fun `a narrow widget drops it rather than ellipsising a sentence into nothing`() {
        // "connected for 1:23:45" under a pair's name is a sentence, not a figure. Two cells
        // across truncates it to a word and a half, and the row's colour already says which pair
        // is the one that is on.
        assertNull(chronometer(DpSize(cells(2), cells(2))))
    }

    @Test
    fun `nothing connected means nothing ticking`() {
        // Hidden rather than left out — see ConnectedFor. What a reader can see is the assertion
        // worth making either way.
        assertFalse(ticking(DpSize(cells(4), cells(2)), openSince = null))
    }

    @Test
    fun `a pair that disconnects loses its timer on a view the launcher reuses`() {
        // A launcher does not re-inflate a widget it already has: AppWidgetHostView recycles the
        // view and reapplies the new RemoteViews onto it. A tree that changed shape lands one
        // view's action on another, throws, and leaves the launcher showing what it had — with a
        // Chronometer the system goes on ticking, counting a session that ended. This is the
        // regression: it fails with "FrameLayout doesn't have method: setMaxLines" if the
        // connected and disconnected rows stop composing to the same shape.
        val view = compose(openSince = now - hour).apply(app, FrameLayout(app))
        assertTrue(shown(view))

        compose(openSince = null).reapply(app, view)
        assertFalse(shown(view))
    }

    private fun ticking(size: DpSize, openSince: Long? = now - hour): Boolean =
        chronometer(size, openSince)?.visibility == View.VISIBLE

    private fun shown(view: View): Boolean =
        view.findViewById<View>(R.id.widget_chronometer)?.visibility == View.VISIBLE

    private fun chronometer(size: DpSize, openSince: Long? = now - hour): View? =
        compose(openSince, size).apply(app, FrameLayout(app)).findViewById(R.id.widget_chronometer)

    private fun compose(openSince: Long?, size: DpSize = DpSize(cells(4), cells(2))): RemoteViews {
        val data = WidgetData(
            rows = listOf(WidgetRow(pairId = 7L, label = "Galaxy Buds3 Pro", lifetimeMs = 40 * hour, openSince = openSince)),
            totalMs = 40 * hour,
            todayMs = hour,
            weekMs = 7 * hour,
            monthMs = 30 * hour,
            series = listOf(LocalDate.of(2026, 3, 1) to hour),
            counting = it.eldavo.ylih.stats.Counting.CONNECTED,
            now = now,
        )
        return runBlocking {
            GlanceRemoteViews().compose(context = app, size = size) { LifetimeContent(app, data) }
                .remoteViews
        }
    }
}
