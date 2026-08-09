package it.eldavo.ylih.widget

import android.content.Context
import org.junit.Assert.assertEquals
import org.xmlpull.v1.XmlPullParser

/**
 * The sizes a launcher will let someone drag a widget to, read out of its own
 * `res/xml/widget_*_info.xml`.
 *
 * Shared rather than copied because two tests ask the same question of the same file and must not
 * answer it differently: [WidgetProvidersTest] checks that every size in the range has a layout to
 * fill it, and [WidgetReapplyTest] checks that every size can be redrawn onto every other. A copy
 * of this arithmetic in each is a copy that can end up sweeping a range the other does not.
 */
internal object ProviderSizes {

    /**
     * The `<appwidget-provider>` attributes, in dp. Read from the compiled resource rather than
     * the source file, so this sees what the launcher will be handed.
     */
    fun dimens(context: Context, providerInfo: Int): (String) -> Float {
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

    /** Runs [check] over every size the provider hands the launcher. */
    fun sweep(context: Context, providerInfo: Int, check: (width: Float, height: Float) -> Unit) {
        val dimen = dimens(context, providerInfo)
        val widths = spread(dimen("minResizeWidth"), dimen("maxResizeWidth"))
        val heights = spread(dimen("minResizeHeight"), dimen("maxResizeHeight"))
        widths.forEach { width -> heights.forEach { height -> check(width, height) } }
    }

    /** Both ends of the range and every 10dp in between — a drag handle moves in pixels. */
    fun spread(from: Float, to: Float): List<Float> =
        generateSequence(from) { it + 10f }.takeWhile { it < to }.toList() + to

    private val DIMENSION = Regex("""^-?\d+(\.\d+)?""")
}
