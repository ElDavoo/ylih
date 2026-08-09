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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.R
import it.eldavo.ylih.data.PairSummary
import it.eldavo.ylih.stats.Counting
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
    // The minute clock: a card shows hours, and each one re-walks its pair's history to find its
    // last seven days. On the second clock that was every card, every second, forever.
    val now by viewModel.nowMinute.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    // One pass, remembered: both halves were re-derived on every recomposition, and the two
    // sections below were the same nine lines twice over.
    val sections = remember(summaries) {
        summaries.partition { it.retiredAt == null }
            .let { (active, retired) ->
                listOf(R.string.devices_in_use to active, R.string.devices_retired to retired)
            }
    }

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
        sections.forEach { (header, pairs) ->
            if (pairs.isEmpty()) return@forEach
            item { SectionHeader(stringResource(header)) }
            items(pairs, key = { it.pairId }) { summary ->
                PairCard(
                    summary = summary,
                    spans = spansByPair[summary.pairId].orEmpty(),
                    now = now,
                    zone = zone,
                    counting = counting,
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
    counting: Counting,
    onClick: () -> Unit,
) {
    val lifetimeMs = summary.countedMs(now, counting)
    val last7 = remember(spans, now, counting, zone) {
        Stats.recentMs(spans, zone, now, days = 7, counting = counting)
    }

    val kind = summary.deviceKind.displayName()
    val generation = stringResource(R.string.devices_generation, summary.generation)
    val retired = summary.retiredAt
        ?.let { stringResource(R.string.devices_retired_on, formatDate(it)) }
    val connectedFor = summary.openSince?.let {
        stringResource(R.string.devices_connected_for, formatDurationShort(now - it))
    }
    val sessionCount = pluralStringResource(
        R.plurals.session_count,
        summary.sessionCount,
        summary.sessionCount,
    )
    val recent = stringResource(R.string.devices_recent, formatHours(last7)).takeIf { last7 > 0 }

    // The card is one thing to a screen reader, not five. It is clickable and so are the three
    // chips inside it — all four with the *same* action — which announced four buttons per pair
    // that all did the same thing. The chips are informational, so their semantics are cleared
    // below and everything they say is gathered here instead.
    val description = listOfNotNull(
        summary.label,
        kind,
        generation.takeIf { summary.generation > 1 },
        retired,
        formatHours(lifetimeMs),
        connectedFor,
        sessionCount,
        recent,
    ).joinToString(" · ")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
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
                // Cleared: see the card's description above. They stay clickable so a tap anywhere
                // on the card still opens the pair, and stop being four announcements of it.
                modifier = Modifier.clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                connectedFor?.let {
                    AssistChip(
                        onClick = onClick,
                        label = { Text(it) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                AssistChip(onClick = onClick, label = { Text(sessionCount) })
                recent?.let { AssistChip(onClick = onClick, label = { Text(it) }) }
            }
        }
    }
}
