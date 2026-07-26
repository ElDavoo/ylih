package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.R
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.stats.Span
import it.eldavo.ylih.stats.Stats
import java.time.ZoneId

@Composable
fun DevicesScreen(
    viewModel: YlihViewModel,
    contentPadding: PaddingValues,
    onOpenPair: (Long) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val spansByPair by viewModel.spansByPair.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    val active = summaries.filter { it.retiredAt == null }
    val retired = summaries.filter { it.retiredAt != null }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        if (summaries.isEmpty()) {
            item { EmptyState() }
        }
        if (active.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.devices_in_use)) }
            items(active, key = { it.pairId }) { summary ->
                PairCard(
                    summary = summary,
                    spans = spansByPair[summary.pairId].orEmpty(),
                    now = now,
                    zone = zone,
                    onClick = { onOpenPair(summary.pairId) },
                )
            }
        }
        if (retired.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.devices_retired)) }
            items(retired, key = { it.pairId }) { summary ->
                PairCard(
                    summary = summary,
                    spans = spansByPair[summary.pairId].orEmpty(),
                    now = now,
                    zone = zone,
                    onClick = { onOpenPair(summary.pairId) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.devices_empty_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.devices_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PairCard(
    summary: PairSummary,
    spans: List<Span>,
    now: Long,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val lifetimeMs = summary.closedMs + (summary.openSince?.let { now - it } ?: 0L)
    val last7 = Stats.recentMs(spans, zone, now, days = 7)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        // Expressive leans on generous, obviously-rounded containers.
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = summary.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val kind = summary.deviceKind.displayName()
                    val generation = stringResource(R.string.devices_generation, summary.generation)
                    val retired = summary.retiredAt
                        ?.let { stringResource(R.string.devices_retired_on, formatDate(it)) }
                    Text(
                        text = listOfNotNull(
                            kind,
                            generation.takeIf { summary.generation > 1 },
                            retired,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatHours(lifetimeMs),
                    // The lifetime figure is the reason the app exists; Expressive's emphasized
                    // cut is heavier and tighter, so it carries the card without growing.
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
            }
            Spacer(Modifier.height(12.dp))
            // A connected pair with 7-day history shows three chips, which do not fit across a
            // 360dp screen. A Row does not wrap: the last chip was squeezed to zero width and its
            // label then wrapped one character per line, stretching the card to a blank column.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                summary.openSince?.let { since ->
                    AssistChip(
                        onClick = onClick,
                        label = {
                            Text(
                                stringResource(
                                    R.string.devices_connected_for,
                                    formatDurationShort(now - since),
                                ),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            pluralStringResource(
                                R.plurals.session_count,
                                summary.sessionCount,
                                summary.sessionCount,
                            ),
                        )
                    },
                )
                if (last7 > 0) {
                    AssistChip(
                        onClick = onClick,
                        label = {
                            Text(stringResource(R.string.devices_recent, formatHours(last7)))
                        },
                    )
                }
            }
        }
    }
}
