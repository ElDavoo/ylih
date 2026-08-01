package it.eldavo.ylih.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.ui.formatHours

/**
 * The grand total across every pair, retired ones included, beside the three windows the stats
 * screen leads with. One row tall: this is the glanceable summary, not the list.
 */
class ActivityWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (localized, data) = widgetContent(context)
        provideContent { ActivityContent(localized, data) }
    }

    private companion object {
        val NARROW = DpSize(cells(2), cells(1))
        val WIDE = DpSize(cells(4), cells(1))
    }
}

/** Internal for the synthetic-accessor reason spelled out in [LifetimeContent]. */
@Composable
internal fun ActivityContent(context: Context, data: WidgetData) {
    // Half a row across fits two figures legibly and no more; squeezing four in would leave
    // captions nobody can read, which is worse than not showing them.
    val wide = LocalSize.current.width >= cells(4)
    WidgetRoot(context) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetTile(
                value = formatHours(data.totalMs),
                // One row leaves no space for a note under the headline, so the headline's own
                // caption has to name what is being counted — a lifetime total that has quietly
                // halved is exactly what this app must never show without saying why. It names it
                // with stats_playing rather than the stats screen's full stats_playback_only_note,
                // because a quarter of a row truncates that sentence to "counting play…", and a
                // warning nobody can finish reading is worse than the short true label.
                label = context.getString(
                    if (data.counting == Counting.PLAYBACK) {
                        R.string.stats_playing
                    } else {
                        R.string.stats_title
                    },
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            WidgetTile(
                value = formatHours(data.todayMs),
                label = context.getString(R.string.stats_today),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (wide) {
                WidgetTile(
                    value = formatHours(data.weekMs),
                    label = context.getString(R.string.stats_last_7),
                    modifier = GlanceModifier.defaultWeight(),
                )
                WidgetTile(
                    value = formatHours(data.monthMs),
                    label = context.getString(R.string.stats_last_30),
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    }
}
