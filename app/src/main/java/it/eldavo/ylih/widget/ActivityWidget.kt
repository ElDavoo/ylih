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
 * screen leads with. As many of the four as fit, laid out for the size the launcher actually gave
 * us rather than for the nearest of a couple of fixed shapes.
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
        // One row leaves no space for a note under the headline, so the headline's own caption
        // has to name what is being counted — a lifetime total that has quietly halved is exactly
        // what this app must never show without saying why. It names it with stats_playing rather
        // than the stats screen's full stats_playback_only_note, because a quarter of a row
        // truncates that sentence to "counting play…", and a warning nobody can finish reading is
        // worse than the short true label.
        formatHours(data.totalMs) to context.getString(
            if (data.counting == Counting.PLAYBACK) R.string.stats_playing else R.string.stats_title,
        ),
        formatHours(data.todayMs) to context.getString(R.string.stats_today),
        formatHours(data.weekMs) to context.getString(R.string.stats_last_7),
        formatHours(data.monthMs) to context.getString(R.string.stats_last_30),
    )
    WidgetRoot(context) {
        // The block is centred rather than stretched: weighting the lines themselves would put
        // most of a tall widget's height in the gap between them, and this one can now be dragged
        // to shapes far taller than the figures need.
        Spacer(GlanceModifier.defaultWeight())
        // Each line takes the next few figures; the grid says how many, and the four are in
        // lifetime-first order because that is the one this app exists to show.
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
 * Half a row across fits two figures legibly and no more; squeezing four in would leave captions
 * nobody can read, which is worse than not showing them. The second line only appears when there is
 * height for it, and then it is used even where all four would fit across — a widget as tall as it
 * is wide reads better as a block than as one long line.
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
