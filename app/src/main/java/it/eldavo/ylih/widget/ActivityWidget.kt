package it.eldavo.ylih.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.ui.formatHours

/**
 * The grand total across every pair, retired ones included, beside the three windows the stats
 * screen leads with. As many of the four as fit, laid out for the launcher's actual size rather
 * than the nearest of a couple of fixed shapes.
 */
class ActivityWidget : YlihWidget() {

    @Composable
    override fun Content(context: Context, data: WidgetData) = ActivityContent(context, data)
}

/** Internal for the synthetic-accessor reason spelled out in [LifetimeContent]. */
@Composable
internal fun ActivityContent(context: Context, data: WidgetData) {
    val size = LocalSize.current
    val grid = activityGrid(size.width.value, size.height.value)
    val tiles = listOf(
        // One row leaves no space for a note under the headline, so the caption itself must name
        // what's counted — a lifetime total that quietly halved must never go unexplained. It uses
        // stats_playing, not the stats screen's full stats_playback_only_note, because a quarter of
        // a row truncates that sentence to "counting play…", and an unfinishable warning is worse
        // than a short true label.
        formatHours(data.totalMs) to context.getString(
            if (data.counting == Counting.PLAYBACK) R.string.stats_playing else R.string.stats_title,
        ),
        formatHours(data.todayMs) to context.getString(R.string.stats_today),
        formatHours(data.weekMs) to context.getString(R.string.stats_last_7),
        formatHours(data.monthMs) to context.getString(R.string.stats_last_30),
    )
    WidgetRoot(context) {
        // Centred, not stretched: weighting the lines themselves would put most of a tall widget's
        // height in the gap between them, and it can be dragged far taller than the figures need.
        Spacer(GlanceModifier.defaultWeight())
        // Each line takes the next few figures per the grid; lifetime leads because it's the figure
        // this app exists to show.
        var taken = 0
        grid.forEachIndexed { index, line ->
            if (index > 0) Spacer(GlanceModifier.height(8.dp))
            val figures = tiles.subList(taken, taken + line)
            taken += line
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                figures.forEach { (value, label) ->
                    WidgetTile(value = value, label = label, modifier = GlanceModifier.defaultWeight())
                }
            }
        }
        Spacer(GlanceModifier.defaultWeight())
    }
}

/**
 * How many figures each line of the activity widget holds, at the size the launcher gave us.
 *
 * Half a row fits two figures legibly; squeezing in four leaves captions nobody can read. The
 * second line appears only when there's height for it, and is then used even where all four would
 * fit across — a widget as tall as it is wide reads better as a block than one long line.
 */
internal fun activityGrid(widthDp: Float, heightDp: Float): List<Int> {
    val across = fits(widthDp, TILE_WIDTH_DP, max = TILES)
    val down = fits(heightDp - VERTICAL_PADDING_DP, TILE_HEIGHT_DP, max = 2)
    val count = minOf(TILES, across * down)
    val lines = if (down > 1 && count > 1) 2 else 1
    val perLine = (count + lines - 1) / lines
    return List(lines) { line -> minOf(perLine, count - line * perLine) }
}

/** The four windows [ActivityContent] can draw: lifetime, today, 7 days, 30 days. */
private const val TILES = 4

/** A figure over its caption needs about this much room before the caption starts truncating. */
private const val TILE_WIDTH_DP = 55f
private const val TILE_HEIGHT_DP = 40f
