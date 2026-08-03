package it.eldavo.ylih.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import it.eldavo.ylih.R

/**
 * That the two halves of a widget's declaration agree.
 *
 * A Glance widget says how big it can be twice: in `res/xml/widget_*_info.xml`, which is what the
 * launcher enforces when someone drags the handles, and in `sizeMode`, which is what the layout
 * code is prepared for. Nothing makes them agree. These used to be `SizeMode.Responsive` sets, and
 * a widget dragged to a size between two buckets drew the smaller layout with a band of empty
 * background under it — so the providers could not offer a wide resize range without offering
 * sizes no layout had been written for. Every widget is now `SizeMode.Exact` and sizes itself off
 * the launcher's real measurements, which is what lets the range below be as wide as the platform
 * will honour; the sweep is what keeps the arithmetic honest across all of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetProvidersTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each receiver hosts its own widget`() {
        // Glance resolves a widget back to its receiver by instantiating every receiver the
        // manifest names and asking each which GlanceAppWidget it holds, so a copy-paste here
        // would send all three widgets' updates to one of them.
        assertTrue(LifetimeWidgetReceiver().glanceAppWidget is LifetimeWidget)
        assertTrue(ActivityWidgetReceiver().glanceAppWidget is ActivityWidget)
        assertTrue(ChartWidgetReceiver().glanceAppWidget is ChartWidget)
    }

    @Test
    fun `every widget is composed for the size it was actually given`() {
        // The alternative is a fixed set of layouts, and then every size between two of them is a
        // size nobody drew.
        assertEquals(SizeMode.Exact, LifetimeWidget().sizeMode)
        assertEquals(SizeMode.Exact, ActivityWidget().sizeMode)
        assertEquals(SizeMode.Exact, ChartWidget().sizeMode)
    }

    @Test
    fun `every widget the manifest names loads its figures through YlihWidget`() {
        // The one thing a widget must not do is read its figures once: Glance runs provideGlance
        // only to *start a session*, and the composition then lives for about 45 seconds, so a
        // widget holding what it loaded redraws those same numbers at every refresh arriving in
        // that window — which is how a connect used to reach the home screen a minute late.
        // YlihWidget closes that by making provideGlance final, and this reads the merged manifest
        // rather than the three receivers by name so that a fourth widget cannot be added by
        // another door and quietly reintroduce it.
        val receivers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS)
            .receivers
            .orEmpty()
            .map { Class.forName(it.name) }
            .filter { GlanceAppWidgetReceiver::class.java.isAssignableFrom(it) }

        assertEquals("every widget receiver in the manifest", 3, receivers.size)
        receivers.forEach {
            val widget = (it.getDeclaredConstructor().newInstance() as GlanceAppWidgetReceiver)
                .glanceAppWidget
            assertTrue("${it.simpleName} hosts a ${widget.javaClass.simpleName}", widget is YlihWidget)
        }
    }

    @Test
    fun `every widget can be dragged both ways and across the whole grid`() {
        assertResizable(R.xml.widget_lifetime_info)
        assertResizable(R.xml.widget_activity_info)
        assertResizable(R.xml.widget_chart_info)
    }

    @Test
    fun `every size the provider allows has a layout to fill it`() {
        sweep(R.xml.widget_lifetime_info) { _, height ->
            val rows = lifetimeRows(height, header = height >= cells(2).value)
            assertTrue("${height}dp tall asks for $rows rows", rows in 1..12)
        }
        sweep(R.xml.widget_activity_info) { width, height ->
            val grid = activityGrid(width, height)
            val at = "${width}x${height}dp"
            assertTrue("$at draws no figures", grid.isNotEmpty() && grid.all { it >= 1 })
            assertTrue("$at draws ${grid.sum()} of four figures", grid.sum() in 1..4)
        }
    }

    @Test
    fun `a taller widget lists more pairs, up to a payload a launcher can carry`() {
        // Every row is a PendingIntent and possibly a Chronometer inside a RemoteViews, which is
        // an IPC payload; a widget dragged the height of the screen must not build one out of
        // forty of them.
        assertEquals(1, lifetimeRows(cells(1).value, header = false))
        assertEquals(3, lifetimeRows(cells(2).value, header = true))
        assertEquals(8, lifetimeRows(cells(4).value, header = true))
        assertEquals(12, lifetimeRows(640f, header = true))
    }

    @Test
    fun `a short list stops sharing out height once the gaps outgrow the rows`() {
        // Three pairs in a widget two cells tall share the height between them; the same three
        // spread down a widget five cells tall would sit a row's own height apart, which reads as
        // a list with holes in it rather than a short list.
        assertTrue(lifetimeStretches(cells(2).value, header = true, pairs = 3))
        assertFalse(lifetimeStretches(cells(5).value, header = true, pairs = 3))
        // A widget full to the brim always shares: rows crammed at the top with a band of empty
        // background under them look broken rather than roomy.
        val tall = cells(5).value
        assertTrue(lifetimeStretches(tall, header = true, pairs = lifetimeRows(tall, header = true)))
    }

    @Test
    fun `a widget with height to spare stacks its figures rather than stretching them`() {
        // One cell tall is the shape this widget was born in: everything on one line.
        assertEquals(listOf(4), activityGrid(cells(4).value, cells(1).value))
        assertEquals(listOf(2), activityGrid(cells(2).value, cells(1).value))
        assertEquals(listOf(1), activityGrid(cells(1).value, cells(1).value))
        // Given a second row it uses one, even where all four would fit across: a widget as tall
        // as it is wide reads better as a block than as one long line.
        assertEquals(listOf(2, 2), activityGrid(cells(4).value, cells(2).value))
        assertEquals(listOf(1, 1), activityGrid(cells(1).value, cells(2).value))
        // And it never grows past the four windows it has.
        assertEquals(listOf(2, 2), activityGrid(640f, 640f))
    }

    /** That the provider offers a range at all, in both directions, and does not contradict itself. */
    private fun assertResizable(providerInfo: Int) {
        val dimen = providerDimens(providerInfo)
        val name = context.resources.getResourceEntryName(providerInfo)

        assertEquals(
            "$name cannot be dragged in both directions",
            AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL,
            resizeMode(providerInfo),
        )
        assertTrue(
            "$name declares a minResizeWidth above the size it is placed at",
            dimen("minResizeWidth") <= dimen("minWidth"),
        )
        assertTrue(
            "$name declares a minResizeHeight above the size it is placed at",
            dimen("minResizeHeight") <= dimen("minHeight"),
        )
        // Five cells is a phone's home screen filled edge to edge; a tablet's grid is wider still,
        // and the launcher clamps whatever is declared here down to the real one. Anything less is
        // a widget that cannot be made as big as the screen it is on.
        assertTrue(
            "$name cannot be dragged to ${FULL_GRID_DP}dp, which is a phone screen across",
            dimen("maxResizeWidth") >= FULL_GRID_DP && dimen("maxResizeHeight") >= FULL_GRID_DP,
        )
    }

    /** Runs [check] over the corners and the middle of the range the provider hands the launcher. */
    private fun sweep(providerInfo: Int, check: (width: Float, height: Float) -> Unit) {
        val dimen = providerDimens(providerInfo)
        val widths = spread(dimen("minResizeWidth"), dimen("maxResizeWidth"))
        val heights = spread(dimen("minResizeHeight"), dimen("maxResizeHeight"))
        widths.forEach { width -> heights.forEach { height -> check(width, height) } }
    }

    /** Both ends of the range and every 10dp in between — a drag handle moves in pixels. */
    private fun spread(from: Float, to: Float): List<Float> =
        generateSequence(from) { it + 10f }.takeWhile { it < to }.toList() + to

    /**
     * The `<appwidget-provider>` attributes, in dp. Read from the compiled resource rather than
     * the source file, so this sees what the launcher will be handed.
     */
    private fun providerDimens(providerInfo: Int): (String) -> Float {
        val parser = context.resources.getXml(providerInfo)
        @Suppress("ControlFlowWithEmptyBody")
        while (parser.next() != XmlPullParser.START_TAG);
        assertEquals("appwidget-provider", parser.name)
        val dimens = (0 until parser.attributeCount).associate { i ->
            // Compiled dimensions come back as "250.0dip"; only the number matters here.
            parser.getAttributeName(i) to
                DIMENSION.find(parser.getAttributeValue(i))?.value?.toFloat()
        }
        return { attribute ->
            requireNotNull(dimens[attribute]) { "$attribute is not declared, or is not a dimension" }
        }
    }

    /** `resizeMode` is a flag rather than a dimension, so it comes out of the parser differently. */
    private fun resizeMode(providerInfo: Int): Int {
        val parser = context.resources.getXml(providerInfo)
        @Suppress("ControlFlowWithEmptyBody")
        while (parser.next() != XmlPullParser.START_TAG);
        return parser.getAttributeIntValue(ANDROID_NS, "resizeMode", 0)
    }

    private companion object {
        val DIMENSION = Regex("""^-?\d+(\.\d+)?""")
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Five launcher cells: a phone's home screen, corner to corner. */
        const val FULL_GRID_DP = 320f
    }
}
