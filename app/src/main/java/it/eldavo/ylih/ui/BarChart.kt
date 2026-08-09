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
     * What the chart is of, for a screen reader. A `Canvas` has nothing inside it to describe
     * itself, so without this the app's whole visualisation reads as an empty leaf and TalkBack
     * gets three tiny axis labels and nothing else.
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
    // Assembled from strings that already exist in all 77 languages rather than adding one more
    // for a line only a screen reader hears; the separator follows the app bar's own house style.
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
        // Merged away from the screen reader: the description above already carries the range and
        // the peak, and three loose fragments after it would only repeat them out of order.
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

/** The scale the tallest bar means. Clamped so a window with no listening still divides. */
internal fun chartMaxMs(series: List<Pair<LocalDate, Long>>): Long =
    series.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L

/**
 * The bars themselves, in whatever [DrawScope] is handed to them.
 *
 * Pulled out of the Canvas above so the home-screen chart widget can draw the same geometry into
 * a bitmap: Glance has no Canvas of its own, and its layout gives equal weights only, so
 * proportional bar heights cannot be expressed there at all.
 */
internal fun DrawScope.drawDailyBars(
    series: List<Pair<LocalDate, Long>>,
    maxMs: Long,
    barColor: Color,
    trackColor: Color,
) {
    if (series.isEmpty()) return
    val slot = size.width / series.size
    val barWidth = (slot * 0.62f).coerceAtLeast(1.5f)
    val radius = CornerRadius(barWidth / 2, barWidth / 2)
    series.forEachIndexed { index, (_, ms) ->
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
