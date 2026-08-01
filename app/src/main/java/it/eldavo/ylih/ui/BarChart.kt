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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
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
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val maxMs = chartMaxMs(series)
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            drawDailyBars(series, maxMs, barColor, trackColor)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            val first = series.firstOrNull()?.first
            val last = series.lastOrNull()?.first
            Text(
                text = first?.let { formatDayLabel(it) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.chart_max, formatHours(maxMs)),
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
