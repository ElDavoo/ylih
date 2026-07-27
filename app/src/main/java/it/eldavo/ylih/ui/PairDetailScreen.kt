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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.R
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Stats
import java.time.ZoneId

@Composable
fun PairDetailScreen(
    viewModel: YlihViewModel,
    pairId: Long,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val summary by viewModel.summary(pairId).collectAsStateWithLifecycle(initialValue = null)
    val sessions by viewModel.sessions(pairId).collectAsStateWithLifecycle(initialValue = emptyList())
    val now by viewModel.now.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingPurchase by remember { mutableStateOf(false) }
    var retiring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val current = summary
    val spans = sessions.map { it.toSpan() }
    val stats = Stats.summarize(spans, now, counting)

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
                                formatDurationShort(now - it),
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
                    StatRow(
                        listOf(
                            stringResource(R.string.stats_today) to
                                formatHours(Stats.recentMs(spans, zone, now, 1, counting)),
                            stringResource(R.string.stats_last_7) to
                                formatHours(Stats.recentMs(spans, zone, now, 7, counting)),
                            stringResource(R.string.stats_last_30) to
                                formatHours(Stats.recentMs(spans, zone, now, 30, counting)),
                        ),
                    )
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
                    Text(
                        stringResource(R.string.pair_daily_hours_14),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Spacer(Modifier.height(8.dp))
                    DailyBarChart(
                        series = Stats.dailySeries(spans, zone, now, days = 14, counting = counting),
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.pair_sessions)) }
            items(sessions, key = { it.id }) { session ->
                SessionRow(session = session, now = now)
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
            initial = current.priceCents?.let { (it / 100).toString() }.orEmpty(),
            label = stringResource(R.string.pair_price_label),
            supporting = stringResource(R.string.pair_price_supporting),
            onDismiss = { editingPurchase = false },
            onConfirm = { value ->
                val cents = value.trim().toDoubleOrNull()?.let { (it * 100).toLong() }
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
