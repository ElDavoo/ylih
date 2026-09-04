package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.R
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.stats.ChargeSummary
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Cycle
import it.eldavo.ylih.stats.Stats
import java.time.ZoneId

/** Two weeks reads better on a single pair than a month of mostly-empty bars. */
private const val PAIR_CHART_DAYS = 14

/**
 * How many charge cycles are listed under the chart of them.
 *
 * A cap rather than the lot, and for a reason the day list does not have: the days are already
 * bounded by their window, while cycles accumulate for the life of the pair. Listing every one
 * would put an unbounded scroll between the chart and the sessions below it.
 */
private const val PAIR_CYCLE_ROWS = 12

/** Bars the cycle chart draws at most; beyond this, runs of cycles are averaged together. */
private const val MAX_CYCLE_BARS = 40

@Composable
fun PairDetailScreen(
    viewModel: YlihViewModel,
    pairId: Long,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    // Remembered because these are functions rather than properties: each call builds a new Flow,
    // and `collectAsStateWithLifecycle` keys its collection on the instance it was handed. Without
    // this both Room subscriptions were torn down and re-established on every recomposition — at
    // least once a minute, since the body below reads the minute clock, and again on every write
    // that moves the summary. MainActivity's `openPairFlow` records the same trap.
    val summaryFlow = remember(pairId) { viewModel.summary(pairId) }
    val sessionsFlow = remember(pairId) { viewModel.sessions(pairId) }
    val chargeFlow = remember(pairId) { viewModel.chargeSummary(pairId) }
    val summary by summaryFlow.collectAsStateWithLifecycle(initialValue = null)
    val sessions by sessionsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val charge by chargeFlow.collectAsStateWithLifecycle(initialValue = null)
    val spansByPair by viewModel.spansByPair.collectAsStateWithLifecycle()
    // Two clocks: [liveNow] drives the "connected for …" line and the open session's row, which
    // are the only things here meant to move every second. Everything else is derived from this
    // pair's whole history and reads the minute clock — see YlihViewModel.nowMinute.
    val liveNow by viewModel.now.collectAsStateWithLifecycle()
    val now by viewModel.nowMinute.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingPurchase by remember { mutableStateOf(false) }
    var retiring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val current = summary
    // Off the aggregate, not out of every session this pair has ever had. `Stats.summarize` answers
    // the same question from a `List<Span>` and is what stood here — which meant re-summarising the
    // pair's whole history on the main thread every minute, for a figure SQL had already grouped
    // and a cost that grows for as long as the pair is used. `SummarizeLifetimeTest` is what says
    // the two agree; the stats and devices screens moved this way already.
    val stats = remember(current, now, counting) {
        listOfNotNull(current).summarizeLifetime(now, counting)
    }
    // The thirty-day window, which is the same one the devices screen reads for its cards. A chart
    // cannot draw more than it holds, and unlike this pair's history the window is bounded.
    val spans = spansByPair[pairId].orEmpty()
    val series = remember(spans, now, counting, zone) {
        Stats.dailySeries(spans, zone, now, days = WINDOW_DAYS, counting = counting)
    }
    // The same 14-day tail the chart draws, not the 30-day series it's cut from — so the list
    // below the chart and the chart itself agree on both which days appear and what scale they're
    // drawn to, the way StatsScreen's chart and list agree over the full 30 days.
    val breakdown = remember(series) { dailyBreakdown(series.takeLast(PAIR_CHART_DAYS)) }
    val chartMax = remember(series) { chartMaxMs(series.takeLast(PAIR_CHART_DAYS)) }
    // Newest first, like the day list, and numbered from the oldest so that a run of them reads as
    // the history it is — cycle 137 stays cycle 137 however few of them are listed. Only the most
    // recent are: a pair worn daily for a decade has some three thousand cycles, and a list of
    // those would bury the session list under it and never end. The chart carries the whole
    // lifetime, which is where the shape is read anyway.
    val cycleRows = remember(charge) {
        charge?.cycles.orEmpty().withIndex().reversed().take(PAIR_CYCLE_ROWS)
    }
    val cycleMax = remember(cycleRows) { barMaxMs(cycleRows.map { it.value.countedMs }) }

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                title = { Text(current?.label ?: stringResource(R.string.pair_fallback_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.pair_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.pair_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pair_menu_rename)) },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pair_menu_purchase)) },
                            onClick = { menuOpen = false; editingPurchase = true },
                        )
                        if (current?.retiredAt == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pair_menu_retire)) },
                                onClick = { menuOpen = false; retiring = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pair_menu_delete)) },
                            onClick = { menuOpen = false; deleting = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = 24.dp,
            ),
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        formatHours(stats.totalMs),
                        style = MaterialTheme.typography.displaySmallEmphasized,
                    )
                    val generation = current?.generation ?: 1
                    Text(
                        listOfNotNull(
                            // The headline is no longer a lifetime when it counts playback, and
                            // this line is the only place on the screen that can say which.
                            if (counting == Counting.PLAYBACK) {
                                stringResource(R.string.stats_playback_only_note)
                            } else {
                                stringResource(R.string.pair_lifetime)
                            },
                            current?.deviceKind?.displayName(),
                            stringResource(R.string.devices_generation, generation)
                                .takeIf { generation > 1 },
                            stats.firstAt?.let {
                                stringResource(R.string.pair_since, formatDate(it))
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    current?.retiredAt?.let { at ->
                        val reason = current.retireReason
                        Text(
                            if (reason != null) {
                                stringResource(R.string.pair_retired_with_reason, formatDate(at), reason)
                            } else {
                                stringResource(R.string.pair_retired_on, formatDate(at))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    stats.openSince?.let {
                        Text(
                            stringResource(
                                R.string.pair_connected_now,
                                formatDurationShort(liveNow - it),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    val none = stringResource(R.string.value_none)
                    StatRow(
                        listOf(
                            stringResource(R.string.pair_sessions) to
                                stats.sessionCount.toString(),
                            stringResource(R.string.pair_average) to
                                formatDurationShort(stats.averageMs),
                            stringResource(R.string.pair_longest) to
                                formatDurationShort(stats.longestMs),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    WindowStatRow(series)
                    if (stats.hasPlaybackData) {
                        Spacer(Modifier.height(8.dp))
                        StatRow(
                            listOfNotNull(
                                // Counting playback, the headline above already *is* this figure.
                                (stringResource(R.string.stats_playing) to
                                    formatHours(stats.playingMs))
                                    .takeIf { counting == Counting.CONNECTED },
                                stringResource(R.string.pair_of_measured) to
                                    percent(stats.playingMs, stats.measuredMs),
                                stringResource(R.string.pair_measured) to
                                    formatHours(stats.measuredMs),
                            ),
                        )
                    }
                    current?.priceCents?.let { price ->
                        Stats.costPerHour(price, stats.totalMs)?.let { perHour ->
                            Spacer(Modifier.height(8.dp))
                            StatRow(
                                listOf(
                                    stringResource(R.string.pair_paid) to formatMoney(price),
                                    stringResource(R.string.pair_per_hour) to
                                        formatPerHour(perHour),
                                    stringResource(R.string.pair_bought) to
                                        (current.purchaseDate?.let { formatDate(it) } ?: none),
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    val chartLabel = stringResource(R.string.pair_daily_hours_14)
                    // A heading, because it titles the day list below the chart as well as the
                    // chart itself — the same one section StatsScreen makes of its own pair.
                    Text(
                        chartLabel,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    DailyBarChart(
                        // The tail of the series already built above, rather than a second walk
                        // over the same history for a shorter window.
                        series = series.takeLast(PAIR_CHART_DAYS),
                        label = chartLabel,
                    )
                }
            }
            // The days themselves, under the chart of them and inside the same heading — the
            // pattern StatsScreen.kt uses for its own chart and list.
            breakdown.firstOrNull()?.first?.let { today ->
                items(breakdown, key = { "day:${it.first}" }) { (date, ms) ->
                    DailyBreakdownRow(date = date, ms = ms, maxMs = chartMax, today = today)
                }
            }
            // Absent until the headphones have actually reported their battery. Plenty never do —
            // the level reaches Android over HFP, Apple's vendor command or BLE's battery service,
            // and a headset that speaks none of them can say nothing here. An empty section
            // explaining that would be a permanent apology on every pair that has one.
            charge?.takeIf { it.hasData }?.let { cycles ->
                item { ChargeCyclesHeader(cycles) }
                items(cycleRows, key = { "cycle:${it.index}" }) { (index, cycle) ->
                    BreakdownRow(
                        title = stringResource(R.string.pair_cycle_number, index + 1),
                        subtitle = cycleDates(cycle),
                        ms = cycle.countedMs,
                        maxMs = cycleMax,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.pair_sessions)) }
            items(sessions, key = { it.id }) { session ->
                // The live clock only for the one session that is still running; a closed row's
                // duration does not depend on the time and would recompose every second for nothing.
                SessionRow(
                    session = session,
                    now = if (session.disconnectedAt == null) liveNow else now,
                )
                HorizontalDivider()
            }
        }
    }

    if (renaming && current != null) {
        TextFieldDialog(
            title = stringResource(R.string.pair_rename_title),
            initial = current.label,
            label = stringResource(R.string.pair_rename_label),
            onDismiss = { renaming = false },
            onConfirm = { viewModel.renamePair(pairId, it); renaming = false },
        )
    }

    if (retiring && current != null) {
        TextFieldDialog(
            title = stringResource(R.string.pair_retire_title),
            initial = "",
            label = stringResource(R.string.pair_retire_label),
            confirmText = stringResource(R.string.pair_retire_confirm),
            supporting = stringResource(R.string.pair_retire_supporting),
            onDismiss = { retiring = false },
            onConfirm = { viewModel.retirePair(pairId, it); retiring = false },
        )
    }

    if (editingPurchase && current != null) {
        TextFieldDialog(
            title = stringResource(R.string.pair_price_title),
            // The whole price, not `cents / 100`: integer division dropped the minor units, so a
            // pair bought for 123.45 opened the dialog reading "123" and confirming it unchanged
            // rewrote the price as 123.00.
            initial = current.priceCents?.let { formatPriceInput(it) }.orEmpty(),
            label = stringResource(R.string.pair_price_label),
            supporting = stringResource(R.string.pair_price_supporting),
            onDismiss = { editingPurchase = false },
            onConfirm = { value ->
                val cents = parsePriceCents(value)
                viewModel.setPurchaseInfo(
                    pairId,
                    current.purchaseDate ?: current.startedAt,
                    cents,
                )
                editingPurchase = false
            },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.pair_delete_title)) },
            text = { Text(stringResource(R.string.pair_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePair(pairId)
                    deleting = false
                    onBack()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The charge-cycle block: what a charge is currently worth, and the chart of what it used to be.
 *
 * The heading titles the chart *and* the cycles listed under it — they are one section, the way the
 * day list under the chart above is — so there is no `SectionHeader` between them.
 */
@Composable
private fun ChargeCyclesHeader(charge: ChargeSummary) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(24.dp))
        val label = stringResource(R.string.pair_charge_cycles)
        Text(
            label,
            style = MaterialTheme.typography.titleSmallEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        StatRow(
            listOf(
                stringResource(R.string.pair_per_charge) to formatHours(charge.msPerCycle),
                stringResource(R.string.pair_cycles) to formatCycles(charge.cyclesFraction),
                stringResource(R.string.pair_charge_watched) to formatHours(charge.countedMs),
            ),
        )
        // Only once there is more than one bar to compare. A single cycle drawn full height says
        // "this is the tallest one" about a series of one, which is the reading it is least able
        // to support — the figures above already report it.
        if (charge.cycles.size > 1) {
            Spacer(Modifier.height(16.dp))
            // Every cycle the pair has ever been through, averaged into at most [MAX_CYCLE_BARS]
            // bars so that a decade of them still draws — and still reads — in one screen width.
            val bars = remember(charge) {
                bucketedBars(charge.cycles.map { it.countedMs }, MAX_CYCLE_BARS)
            }
            CycleBarChart(
                values = bars,
                label = label,
                firstLabel = stringResource(R.string.pair_cycle_number, 1),
                lastLabel = stringResource(R.string.pair_cycle_number, charge.cycles.size),
            )
        }
    }
}

/** When a cycle ran, for the line under its name. Two dates the locale writes for itself. */
private fun cycleDates(cycle: Cycle): String =
    "${formatDate(cycle.startAt)} – ${formatDate(cycle.endAt)}"

@Composable
private fun SessionRow(session: SessionEntity, now: Long) {
    val duration = Stats.durationMs(session.toSpan(), now)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(formatDateTime(session.connectedAt), style = MaterialTheme.typography.bodyMedium)
            val end = session.disconnectedAt
            Text(
                listOfNotNull(
                    if (end == null) {
                        stringResource(R.string.session_ongoing)
                    } else {
                        stringResource(R.string.session_until, formatDateTime(end))
                    },
                    session.playingMs?.let {
                        stringResource(R.string.session_playing, formatDurationShort(it))
                    },
                    stringResource(R.string.session_recovered)
                        .takeIf { session.endReason == EndReason.RECOVERED },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatDurationShort(duration), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TextFieldDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmText: String = stringResource(R.string.action_save),
    supporting: String? = null,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmText) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
