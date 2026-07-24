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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.SessionEntity
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
    val zone = ZoneId.systemDefault()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editingPurchase by remember { mutableStateOf(false) }
    var retiring by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val current = summary
    val spans = sessions.map { it.toSpan() }
    val stats = Stats.summarize(spans, now)

    Scaffold(
        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                title = { Text(current?.label ?: "Pair") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Purchase info") },
                            onClick = { menuOpen = false; editingPurchase = true },
                        )
                        if (current?.retiredAt == null) {
                            DropdownMenuItem(
                                text = { Text("Retire / replaced") },
                                onClick = { menuOpen = false; retiring = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete") },
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
                    Text(formatHours(stats.totalMs), style = MaterialTheme.typography.displaySmall)
                    Text(
                        buildString {
                            append("lifetime on this pair")
                            current?.let { append(" · ${it.deviceKind.displayName()}") }
                            if ((current?.generation ?: 1) > 1) append(" · pair #${current?.generation}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    current?.retiredAt?.let {
                        Text(
                            "Retired ${formatDate(it)}" +
                                (current.retireReason?.let { reason -> " · $reason" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    stats.openSince?.let {
                        Text(
                            "Connected now · ${formatDurationShort(now - it)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    StatRow(
                        listOf(
                            "Sessions" to stats.sessionCount.toString(),
                            "Average" to formatDurationShort(stats.averageMs),
                            "Longest" to formatDurationShort(stats.longestMs),
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    StatRow(
                        listOf(
                            "Last 7 days" to formatHours(Stats.recentMs(spans, zone, now, 7)),
                            "Last 30 days" to formatHours(Stats.recentMs(spans, zone, now, 30)),
                            "First seen" to (stats.firstAt?.let { formatDate(it) } ?: "—"),
                        ),
                    )
                    if (stats.hasPlaybackData) {
                        Spacer(Modifier.height(8.dp))
                        StatRow(
                            listOf(
                                "Playing" to formatHours(stats.playingMs),
                                "Of measured" to percent(stats.playingMs, stats.measuredMs),
                                "Measured" to formatHours(stats.measuredMs),
                            ),
                        )
                    }
                    current?.priceCents?.let { price ->
                        Stats.costPerHour(price, stats.totalMs)?.let { perHour ->
                            Spacer(Modifier.height(8.dp))
                            StatRow(
                                listOf(
                                    "Paid" to formatMoney(price),
                                    "Per hour" to formatPerHour(perHour),
                                    "Bought" to (current.purchaseDate?.let { formatDate(it) } ?: "—"),
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Text("Daily hours (14 days)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    DailyBarChart(series = Stats.dailySeries(spans, zone, now, days = 14))
                }
            }
            item { SectionHeader("Sessions") }
            items(sessions, key = { it.id }) { session ->
                SessionRow(session = session, now = now)
                HorizontalDivider()
            }
        }
    }

    if (renaming && current != null) {
        TextFieldDialog(
            title = "Rename pair",
            initial = current.label,
            label = "Name",
            onDismiss = { renaming = false },
            onConfirm = { viewModel.renamePair(pairId, it); renaming = false },
        )
    }

    if (retiring && current != null) {
        TextFieldDialog(
            title = "Retire this pair",
            initial = "",
            label = "Reason (died, sold, lost…)",
            confirmText = "Retire",
            supporting = "Totals freeze here. Reconnecting the same device starts a fresh pair at zero.",
            onDismiss = { retiring = false },
            onConfirm = { viewModel.retirePair(pairId, it); retiring = false },
        )
    }

    if (editingPurchase && current != null) {
        TextFieldDialog(
            title = "Price paid",
            initial = current.priceCents?.let { (it / 100).toString() }.orEmpty(),
            label = "Amount (whole units)",
            supporting = "Used for the cost-per-hour figure.",
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
            title = { Text("Delete this pair?") },
            text = { Text("Every session recorded for it is deleted too. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePair(pairId)
                    deleting = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
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
            Text(
                buildString {
                    append(
                        when {
                            session.disconnectedAt == null -> "ongoing"
                            else -> "until ${formatDateTime(session.disconnectedAt)}"
                        },
                    )
                    session.playingMs?.let { append(" · ${formatDurationShort(it)} playing") }
                    if (session.endReason == EndReason.RECOVERED) append(" · recovered")
                },
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
    confirmText: String = "Save",
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
