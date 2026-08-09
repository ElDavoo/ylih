package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.eldavo.ylih.R
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.stats.Stats
import java.time.LocalDate

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        // surfaceContainerHigh rather than surfaceVariant: Expressive builds elevation out of the
        // container roles, and surfaceVariant now reads as a flat fill.
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatRow(tiles: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (label, value) ->
            StatTile(label = label, value = value, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The longest window any screen shows. Both screens build a series this long once and read their
 * shorter windows off its tail, so the history is walked once rather than once per figure.
 */
const val WINDOW_DAYS = 30

/** Total of the last [days] buckets of a dense series — see [Stats.dailySeries]. */
fun List<Pair<LocalDate, Long>>.tailMs(days: Int): Long = takeLast(days).sumOf { it.second }

/**
 * Today, the last seven days and the last thirty, all read off one [WINDOW_DAYS]-day series.
 *
 * The stats screen and the pair page showed exactly this, and each built it from three separate
 * `Stats.recentMs` calls — every one of which bucketed the entire history from scratch, keyed on a
 * clock ticking once a second. Three walks became one, and the series is `remember`ed by its
 * callers so it is not rebuilt for a figure that changes by the hour.
 */
@Composable
fun WindowStatRow(series: List<Pair<LocalDate, Long>>, modifier: Modifier = Modifier) {
    StatRow(
        listOf(
            stringResource(R.string.stats_today) to formatHours(series.tailMs(1)),
            stringResource(R.string.stats_last_7) to formatHours(series.tailMs(7)),
            stringResource(R.string.stats_last_30) to formatHours(series.tailMs(WINDOW_DAYS)),
        ),
        modifier,
    )
}

/** Composable because the name is a translated resource, not a constant. */
@Composable
fun DeviceKind.displayName(): String = stringResource(
    when (this) {
        DeviceKind.BLUETOOTH -> R.string.kind_bluetooth
        DeviceKind.BLE -> R.string.kind_ble
        DeviceKind.WIRED -> R.string.kind_wired
        DeviceKind.USB -> R.string.kind_usb
        DeviceKind.UNKNOWN -> R.string.kind_unknown
    },
)
