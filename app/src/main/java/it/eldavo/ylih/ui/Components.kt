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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.eldavo.ylih.R
import it.eldavo.ylih.data.DeviceKind

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
                textAlign = TextAlign.Start,
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
