package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.R
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Stats
import java.time.ZoneId

@Composable
fun StatsScreen(
    viewModel: YlihViewModel,
    contentPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
) {
    val spans by viewModel.allSpans.collectAsStateWithLifecycle()
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    // The minute clock, not the second one: every figure below is derived from the whole history
    // and none of them can display a change faster than that. See YlihViewModel.nowMinute.
    val now by viewModel.nowMinute.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    val summary = remember(spans, now, counting) { Stats.summarize(spans, now, counting) }
    val series = remember(spans, now, counting, zone) {
        Stats.dailySeries(spans, zone, now, days = WINDOW_DAYS, counting = counting)
    }
    val ranking = remember(summaries, now, counting) {
        summaries.sortedByDescending { it.countedMs(now, counting) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatHours(summary.totalMs),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                val sessions = pluralStringResource(
                    R.plurals.session_count,
                    summary.sessionCount,
                    summary.sessionCount,
                )
                Text(
                    summary.firstAt
                        ?.let { stringResource(R.string.stats_sessions_since, sessions, formatDate(it)) }
                        ?: sessions,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Without this the headline just quietly shrinks, and a lifetime total that
                // dropped by half with no explanation is exactly what this app must never do.
                if (counting == Counting.PLAYBACK) {
                    Text(
                        stringResource(R.string.stats_playback_only_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(16.dp))
                WindowStatRow(series)
                Spacer(Modifier.height(8.dp))
                StatRow(
                    listOf(
                        stringResource(R.string.stats_average_session) to
                            formatDurationShort(summary.averageMs),
                        stringResource(R.string.stats_longest_session) to
                            formatDurationShort(summary.longestMs),
                    ),
                )
                if (summary.hasPlaybackData) {
                    Spacer(Modifier.height(8.dp))
                    StatRow(
                        listOfNotNull(
                            // Counting playback, the headline above already *is* this figure.
                            (stringResource(R.string.stats_playing) to
                                formatHours(summary.playingMs))
                                .takeIf { counting == Counting.CONNECTED },
                            stringResource(R.string.stats_measured_span) to
                                formatHours(summary.measuredMs),
                            stringResource(R.string.stats_share) to
                                percent(summary.playingMs, summary.measuredMs),
                        ),
                    )
                }
                Spacer(Modifier.height(24.dp))
                val chartLabel = stringResource(R.string.stats_daily_hours_30)
                Text(chartLabel, style = MaterialTheme.typography.titleSmallEmphasized)
                Spacer(Modifier.height(8.dp))
                DailyBarChart(series = series, label = chartLabel)
                Spacer(Modifier.height(16.dp))
            }
        }
        item { SectionHeader(stringResource(R.string.stats_by_pair)) }
        items(ranking, key = { it.pairId }) { pair ->
            val lifetime = pair.countedMs(now, counting)
            Column {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(pair.label, style = MaterialTheme.typography.bodyLarge)
                    val sessions = pluralStringResource(
                        R.plurals.session_count,
                        pair.sessionCount,
                        pair.sessionCount,
                    )
                    Text(
                        stringResource(
                            if (pair.retiredAt != null) {
                                R.string.stats_pair_row_retired
                            } else {
                                R.string.stats_pair_row
                            },
                            formatHours(lifetime),
                            sessions,
                        ),
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
