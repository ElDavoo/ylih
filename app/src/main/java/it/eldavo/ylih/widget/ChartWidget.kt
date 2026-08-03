package it.eldavo.ylih.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.ui.chartMaxMs
import it.eldavo.ylih.ui.drawDailyBars
import it.eldavo.ylih.ui.formatDayLabel
import it.eldavo.ylih.ui.formatHours
import java.time.LocalDate

/** The stats screen's 30-day bar chart, on the home screen. */
class ChartWidget : YlihWidget() {

    @Composable
    override fun Content(context: Context, data: WidgetData) = ChartContent(context, data)
}

/** Internal for the synthetic-accessor reason spelled out in [LifetimeContent]. */
@Composable
internal fun ChartContent(context: Context, data: WidgetData) {
    val size = LocalSize.current
    val labelled = size.height >= cells(2)
    // Three captions on one line need the width of about four cells; below that the two dates are
    // the pair that says what the bars span, so the tallest-day figure is the one to drop.
    val wide = size.width >= cells(4)
    val maxMs = chartMaxMs(data.series)
    val bars = remember(data.series, size, labelled) {
        renderBars(
            series = data.series,
            maxMs = maxMs,
            widthDp = size.width.value,
            heightDp = size.height.value - if (labelled) CHROME_DP else 0f,
            barColor = accentColor(context),
            trackColor = trackColor(context),
        )
    }
    WidgetRoot(context) {
        if (labelled) {
            WidgetHeader(
                context = context,
                title = context.getString(R.string.stats_daily_hours_30),
                playbackOnly = data.counting == Counting.PLAYBACK,
            )
            Spacer(GlanceModifier.height(4.dp))
        }
        Image(
            provider = ImageProvider(bars),
            contentDescription = context.getString(R.string.stats_daily_hours_30),
            // The bitmap is drawn at the size bucket, not at the widget's real size, so it is
            // stretched rather than letterboxed into whatever the launcher actually gave us.
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        )
        if (labelled) {
            Spacer(GlanceModifier.height(2.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                ChartLabel(data.series.firstOrNull()?.first?.let(::formatDayLabel).orEmpty())
                Spacer(GlanceModifier.defaultWeight())
                if (wide) {
                    ChartLabel(context.getString(R.string.chart_max, formatHours(maxMs)))
                    Spacer(GlanceModifier.defaultWeight())
                }
                ChartLabel(data.series.lastOrNull()?.first?.let(::formatDayLabel).orEmpty())
            }
        }
    }
}

@Composable
private fun ChartLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
    )
}

/**
 * Draws the app's own bars into a bitmap.
 *
 * Glance has no Canvas, and its layout hands out equal weights only, so proportional bar heights
 * cannot be expressed in Glance at all. Drawing [drawDailyBars] into an off-screen bitmap keeps
 * one source of truth for the geometry — the chart on the stats screen and the one on the home
 * screen are the same code.
 *
 * The cost is that a bitmap bakes its colours: flipping dark mode leaves stale bars until the next
 * widget update lands. Everything Glance itself draws below API 31 has the same problem, and the
 * alternative — Boxes sized in dp — loses the rounded track and needs API 31 for its corners.
 */
private fun renderBars(
    series: List<Pair<LocalDate, Long>>,
    maxMs: Long,
    widthDp: Float,
    heightDp: Float,
    barColor: Color,
    trackColor: Color,
): Bitmap {
    // Two pixels per dp rather than the device's density: the bars are flat rectangles, nobody
    // can see the difference, and a RemoteViews carrying a full-density bitmap is a megabyte the
    // launcher has to be handed on every update. Now that the widget can be dragged to the width
    // of a tablet, the ceiling is reached by scaling both sides by the same factor rather than by
    // clamping each: the bitmap is stretched to the real size on the way in, so an aspect ratio
    // clamped on one axis only would draw bars of the wrong width.
    val scale = minOf(PX_PER_DP, MAX_PX / maxOf(widthDp, heightDp, 1f))
    val width = (widthDp * scale).toInt().coerceAtLeast(1)
    val height = (heightDp * scale).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(width.toFloat(), height.toFloat()),
    ) {
        drawDailyBars(series, maxMs, barColor, trackColor)
    }
    return bitmap.asAndroidBitmap()
}

/** Title, day labels and the spacing between them, in dp — what the bars do not get. */
private const val CHROME_DP = 34f

private const val PX_PER_DP = 2f
private const val MAX_PX = 720f
