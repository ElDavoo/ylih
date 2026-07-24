package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Stats
import java.time.ZoneId

@Composable
fun StatsScreen(viewModel: YlihViewModel, contentPadding: PaddingValues) {
    val spans by viewModel.allSpans.collectAsStateWithLifecycle()
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    val summary = Stats.summarize(spans, now)
    val series = Stats.dailySeries(spans, zone, now, days = 30)
    val ranking = summaries.sortedByDescending {
        it.closedMs + (it.openSince?.let { since -> now - since } ?: 0L)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("All headphones", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    formatHours(summary.totalMs),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    pluralStringResource(
                        R.plurals.session_count,
                        summary.sessionCount,
                        summary.sessionCount,
                    ) + (summary.firstAt?.let { " since ${formatDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                StatRow(
                    listOf(
                        "Last 7 days" to formatHours(Stats.recentMs(spans, zone, now, 7)),
                        "Last 30 days" to formatHours(Stats.recentMs(spans, zone, now, 30)),
                        "Average session" to formatDurationShort(summary.averageMs),
                    ),
                )
                if (summary.hasPlaybackData) {
                    Spacer(Modifier.height(8.dp))
                    StatRow(
                        listOf(
                            "Playing" to formatHours(summary.playingMs),
                            "Measured span" to formatHours(summary.measuredMs),
                            "Share" to percent(summary.playingMs, summary.measuredMs),
                        ),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text("Daily hours (30 days)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                DailyBarChart(series = series)
                Spacer(Modifier.height(16.dp))
            }
        }
        item { SectionHeader("By pair") }
        items(ranking, key = { it.pairId }) { pair ->
            val lifetime = pair.closedMs + (pair.openSince?.let { now - it } ?: 0L)
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(pair.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        formatHours(lifetime) + " · " +
                            pluralStringResource(
                                R.plurals.session_count,
                                pair.sessionCount,
                                pair.sessionCount,
                            ) + if (pair.retiredAt != null) " · retired" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

internal fun percent(part: Long, whole: Long): String =
    if (whole <= 0) "—" else "${(part * 100 / whole).coerceIn(0, 100)}%"
