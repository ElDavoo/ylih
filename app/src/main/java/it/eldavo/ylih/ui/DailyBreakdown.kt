package it.eldavo.ylih.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.eldavo.ylih.R
import java.time.LocalDate

/**
 * The days a breakdown lists, newest first.
 *
 * The chart draws this same window, and a bar is a proportion rather than a figure: "how long did
 * I listen yesterday" is a question about a figure, and no bar can be read to a tenth of an hour.
 *
 * Only the leading run of empty days is dropped. An install a fortnight old would otherwise open
 * on sixteen rows of nothing, which reads as history that was lost rather than as history that was
 * never recorded. A zero *inside* the window is a real answer — it is the day nothing was played —
 * and so is a zero today, which is why the recent end is left whole however empty it is.
 */
internal fun dailyBreakdown(series: List<Pair<LocalDate, Long>>): List<Pair<LocalDate, Long>> =
    series.dropWhile { it.second == 0L }.asReversed()

/**
 * How little of the width a day that was listened to at all may take.
 *
 * Twenty minutes against a ten-hour day is a third of a pixel, and a bar that rounds away says
 * "nothing" about a day that was not nothing. The chart clamps its own bars for the same reason.
 */
private const val MIN_BAR_FRACTION = 0.02f

private val BAR_HEIGHT = 6.dp

/**
 * One day: what day it was, how long it came to, and how that compares with the busiest day on
 * screen.
 *
 * [maxMs] is what the longest bar means, and comes from [chartMaxMs] — the same scale the chart
 * above the list is drawn to, so that a day is the same size in both and never divides by zero.
 */
@Composable
fun DailyBreakdownRow(
    date: LocalDate,
    ms: Long,
    maxMs: Long,
    today: LocalDate,
    modifier: Modifier = Modifier,
) = BreakdownRow(
    title = dayName(date, today),
    subtitle = null,
    ms = ms,
    maxMs = maxMs,
    modifier = modifier,
)

/**
 * One labelled figure with a bar under it, drawn against the scale the chart above uses.
 *
 * Shared with the pair page's charge cycles, which are the same shape of thing read the same way —
 * a figure, and how it compares with the largest on screen. What differs between the two is only
 * what the row is *called*, which is why that is all this takes.
 */
@Composable
internal fun BreakdownRow(
    title: String,
    subtitle: String?,
    ms: Long,
    maxMs: Long,
    modifier: Modifier = Modifier,
) {
    val value = formatHours(ms)
    Column(
        modifier
            .fillMaxWidth()
            // Merged, and read title-first, for the reason `StatTile` is: the texts are one
            // figure, and unmerged they arrive as "3.4 h" and then the day it belongs to.
            .semantics(mergeDescendants = true) {
                val name = listOfNotNull(title, subtitle).joinToString(" · ")
                contentDescription = "$name: $value"
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            // The track is drawn for every day and the fill only for a day with something in it:
            // a zero-width rounded box is a layout node that draws nothing.
            if (ms > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((ms.toFloat() / maxMs).coerceIn(MIN_BAR_FRACTION, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

/**
 * "today", "yesterday", or the weekday and the date.
 *
 * The two relative names are the point of the list: the question it exists to answer is "how much
 * did I listen yesterday", and working out which date that was is exactly the arithmetic a reader
 * should not be left to do. Older days carry their weekday as well, because a run of dates is read
 * for its weekly shape and a date on its own does not say whether it was a working day.
 */
@Composable
internal fun dayName(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.stats_today)
    today.minusDays(1) -> stringResource(R.string.stats_yesterday)
    // Joined here rather than through a format string, out of two resources that already exist in
    // every language — the chart's own description is assembled the same way.
    else -> "${formatWeekday(date)} · ${formatDayLabel(date)}"
}
