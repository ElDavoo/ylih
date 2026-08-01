package it.eldavo.ylih.widget

import android.content.Context
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
 * launcher enforces when someone drags the handles, and in `SizeMode.Responsive`, which is the set
 * of layouts it actually has. Nothing makes them agree. Let the smallest bucket be wider than
 * `minResizeWidth` and a user can shrink the widget to a size no layout was written for — Glance
 * falls back to the smallest one it has and the launcher clips the difference, which reads as a
 * broken widget rather than a small one. This caught exactly that on two of the three.
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
    fun `every size the provider allows has a layout to fill it`() {
        assertBucketsFit(LifetimeWidget(), R.xml.widget_lifetime_info)
        assertBucketsFit(ActivityWidget(), R.xml.widget_activity_info)
        assertBucketsFit(ChartWidget(), R.xml.widget_chart_info)
    }

    private fun assertBucketsFit(widget: GlanceAppWidget, providerInfo: Int) {
        val sizes = (widget.sizeMode as SizeMode.Responsive).sizes
        val declared = providerDimens(providerInfo)
        val name = widget::class.simpleName

        val smallest = DpSize(sizes.minOf { it.width }, sizes.minOf { it.height })
        assertTrue(
            "$name can be resized to ${declared("minResizeWidth")}dp wide but its narrowest " +
                "layout assumes ${smallest.width}",
            smallest.width.value <= declared("minResizeWidth"),
        )
        assertTrue(
            "$name can be resized to ${declared("minResizeHeight")}dp tall but its shortest " +
                "layout assumes ${smallest.height}",
            smallest.height.value <= declared("minResizeHeight"),
        )

        val largest = DpSize(sizes.maxOf { it.width }, sizes.maxOf { it.height })
        assertTrue(
            "$name has a ${largest.width} layout it can never be dragged wide enough to use",
            largest.width.value <= declared("maxResizeWidth"),
        )
        assertTrue(
            "$name has a ${largest.height} layout it can never be dragged tall enough to use",
            largest.height.value <= declared("maxResizeHeight"),
        )
    }

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

    private companion object {
        val DIMENSION = Regex("""^-?\d+(\.\d+)?""")
    }
}
