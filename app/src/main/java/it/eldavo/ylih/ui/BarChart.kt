package it.eldavo.ylih.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.eldavo.ylih.R
import java.time.LocalDate

/**
 * Daily hours as plain bars on a Canvas — a charting library would be a large dependency for
 * one rectangle per day.
 */
@Composable
fun DailyBarChart(
    series: List<Pair<LocalDate, Long>>,
    modifier: Modifier = Modifier,
    /**
     * What the chart is of, for a screen reader. A `Canvas` has nothing to describe itself, so
     * without this it reads as an empty leaf and TalkBack gets three tiny axis labels and nothing
     * else.
     */
    label: String? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val maxMs = chartMaxMs(series)
    val first = series.firstOrNull()?.first
    val last = series.lastOrNull()?.first
    val peak = stringResource(R.string.chart_max, formatHours(maxMs))
    val total = formatHours(series.sumOf { it.second })
    // Assembled from strings that already exist in all 77 languages, rather than adding one more
    // for a line only a screen reader hears; separator matches the app bar's house style.
    val description = remember(label, first, last, peak, total) {
        listOfNotNull(
            label,
            first?.let { start -> last?.let { "${formatDayLabel(start)} – ${formatDayLabel(it)}" } },
            peak,
            total,
        ).joinToString(" · ")
    }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = description },
        ) {
            drawDailyBars(series, maxMs, barColor, trackColor)
        }
        Spacer(Modifier.height(4.dp))
        // Merged away from the screen reader: the description above already carries range and
        // peak; three loose fragments after it would just repeat them out of order.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Text(
                text = first?.let { formatDayLabel(it) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = peak,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = last?.let { formatDayLabel(it) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The same bars, one per completed charge cycle instead of one per day.
 *
 * A separate composable, not a parameter on [DailyBarChart]: the axis differs in kind — days carry
 * dates, cycles carry an ordinal — and a cycle is read only against its neighbours, i.e. whether
 * the bars get shorter.
 */
@Composable
fun CycleBarChart(
    values: List<Long>,
    label: String,
    firstLabel: String,
    lastLabel: String,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val maxMs = barMaxMs(values)
    val peak = stringResource(R.string.chart_max, formatHours(maxMs))
    val description = remember(label, firstLabel, lastLabel, peak) {
        listOf(label, "$firstLabel – $lastLabel", peak).joinToString(" · ")
    }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = description },
        ) {
            drawBars(values, maxMs, barColor, trackColor)
        }
        Spacer(Modifier.height(4.dp))
        // Merged away as in DailyBarChart's axis row: the description above already says the
        // range and peak.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Text(
                text = firstLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = peak,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = lastLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The scale the tallest bar means. Clamped so a window with no listening still divides. */
internal fun chartMaxMs(series: List<Pair<LocalDate, Long>>): Long =
    series.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L

/** [chartMaxMs] for a series that has no dates in it. */
internal fun barMaxMs(values: List<Long>): Long =
    values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

/**
 * At most [max] bars, by averaging runs of consecutive values when there are more.
 *
 * A pair used daily for a decade is ~3,000 charge cycles — narrower on a phone than a pixel drawn
 * 3,000 times a frame, so the chart stops being readable before it stops being drawable.
 * Averaging keeps the shape, the only thing read here: whether bars get shorter. The figures
 * above it stay exact, since they're counted rather than drawn.
 */
internal fun bucketedBars(values: List<Long>, max: Int): List<Long> {
    require(max > 0) { "max must be positive" }
    if (values.size <= max) return values
    // Rounded up, so the result can never exceed [max]; the last bucket may be short but is still
    // an average of what's in it.
    val size = (values.size + max - 1) / max
    return values.chunked(size) { run -> run.sum() / run.size }
}

/**
 * The bars themselves, in whatever [DrawScope] is handed to them.
 *
 * Pulled out of the Canvas above so the widget can draw the same geometry into a bitmap: Glance
 * has no Canvas of its own, and its layout gives only equal weights, so proportional bar heights
 * can't be expressed there.
 */
internal fun DrawScope.drawDailyBars(
    series: List<Pair<LocalDate, Long>>,
    maxMs: Long,
    barColor: Color,
    trackColor: Color,
) = drawBars(series.map { it.second }, maxMs, barColor, trackColor)

/**
 * The geometry, stripped of what the bars are *of*.
 *
 * The daily chart, the widget's bitmap and the charge-cycle chart all draw this; keeping it
 * keyless stops a second copy of the arithmetic appearing the moment something charts against a
 * non-date axis.
 */
internal fun DrawScope.drawBars(
    values: List<Long>,
    maxMs: Long,
    barColor: Color,
    trackColor: Color,
) {
    if (values.isEmpty()) return
    val slot = size.width / values.size
    val barWidth = (slot * 0.62f).coerceAtLeast(1.5f)
    val radius = CornerRadius(barWidth / 2, barWidth / 2)
    values.forEachIndexed { index, ms ->
        val left = index * slot + (slot - barWidth) / 2
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(left, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = radius,
        )
        val barHeight = (size.height * (ms.toFloat() / maxMs)).coerceAtLeast(if (ms > 0) 2f else 0f)
        if (barHeight > 0f) {
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}
