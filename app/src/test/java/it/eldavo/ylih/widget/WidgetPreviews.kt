@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package it.eldavo.ylih.widget

import android.os.Build
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.listing.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The pictures the widget picker shows, recorded from the real widgets:
 *
 *     ./gradlew recordRoborazziClassicReleaseTest && git status
 *
 * Unlike the Play listing images beside these, the output is **committed**: `previewImage` is
 * referenced from `res/xml/widget_*_info.xml`, so it has to exist in the tree for the build to
 * compile at all. Nothing in CI regenerates or compares them, which means a widget redesign leaves
 * a stale picture in the picker until someone re-runs the line above.
 *
 * Roborazzi's captures are inert outside a record task, so in the ordinary unit-test run this class
 * costs three compositions and writes nothing.
 *
 * Each widget is photographed at the size a launcher will actually hand it — [placed], not the
 * `cells()` minimum the provider declares. The minimum is a floor for resizing; advertising the
 * widget at it would show three rows crammed into the space of two. The qualifiers say the same
 * size again because the capture is of the whole screen, and xhdpi rather than the listing
 * generators' mdpi because these are drawn once and then scaled by whatever launcher shows them,
 * so twice the pixels costs nothing and survives that scaling.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetPreviews {

    @get:Rule
    val compose = createComposeRule()

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    /**
     * Its own container, on a stopped clock. The listing screenshots anchor DemoData to the moment
     * they are taken so a "last seen" date is never stale, but these files are committed, and a
     * story that moves with the calendar would rewrite three PNGs on every re-record — with the
     * timer, the day labels and the totals all different and none of it a change anyone made.
     */
    private val container = AppContainer(app) { NOW }

    @Before
    fun setUp() {
        // ui/Format.kt puts every duration through Locale.getDefault(), which no resource
        // qualifier reaches — so without this the decimal separator is the recording machine's.
        Locale.setDefault(Locale.US)
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h170dp-land-xhdpi")
    fun `lifetime widget preview`() {
        val data = demoData()
        capture("widget_preview_lifetime", placed(4, 2)) { LifetimeContent(app, data) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h85dp-land-xhdpi")
    fun `activity widget preview`() {
        val data = demoData()
        capture("widget_preview_activity", placed(4, 1)) { ActivityContent(app, data) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h170dp-land-xhdpi")
    fun `chart widget preview`() {
        val data = demoData()
        capture("widget_preview_chart", placed(4, 2)) { ChartContent(app, data) }
    }

    /**
     * The `targetCellWidth`/`targetCellHeight` footprint in dp, as a phone-sized launcher grid
     * hands it out — four columns across a 360dp screen, and rows a little taller than they are
     * wide. Nothing reads these at runtime; they exist so the picture matches the placement.
     */
    private fun placed(columns: Int, rows: Int) = DpSize((columns * 80).dp, (rows * 85).dp)

    /** The same year of plausible listening the Play listing screenshots are built on. */
    private fun demoData(): WidgetData = runBlocking {
        container.database.deviceDao().deleteAll()
        DemoData.seed(container.database, NOW, ZONE)
        loadWidgetData(container, ZONE)
    }

    /**
     * Composes the widget the way the launcher will — into a `RemoteViews` — and then inflates it.
     * Rendering the Glance composables directly would photograph something no phone ever shows;
     * everything that can go wrong with a widget goes wrong in the translation to `RemoteViews`.
     *
     * It is hosted inside the Compose rule's activity only because Roborazzi captures a view
     * through its window, and a bare inflated view has none.
     */
    private fun capture(name: String, size: DpSize, content: @Composable () -> Unit) {
        val remoteViews = runBlocking {
            GlanceRemoteViews().compose(context = app, size = size, content = content).remoteViews
        }
        compose.setContent {
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply { addView(remoteViews.apply(context, this)) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.onRoot().captureRoboImage(File(PREVIEW_DIR, "$name.png"))
    }

    private companion object {
        /**
         * Relative to the module directory, which is where Gradle runs unit tests from. `-nodpi`
         * keeps the launcher from rescaling a picture that is already the right shape.
         */
        const val PREVIEW_DIR = "src/main/res/drawable-nodpi"

        /** The day DemoData's random seed is named after, at a plausible hour of the evening. */
        val NOW: Long = LocalDateTime.of(2026, 7, 25, 18, 40).toInstant(ZoneOffset.UTC).toEpochMilli()

        /** UTC, so the day bucketing is not the recording machine's either. */
        val ZONE: ZoneId = ZoneOffset.UTC
    }
}
