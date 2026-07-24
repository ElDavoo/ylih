package it.eldavo.ylih.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.BuildConfig
import it.eldavo.ylih.Distribution

@Composable
fun SettingsScreen(viewModel: YlihViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val detailed by viewModel.detailedTracking.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> confirmImport = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        val detailedSupported = viewModel.detailedTrackingSupported()
        SectionHeader("Tracking")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setDetailedTracking(!detailed) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Detailed tracking", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Adds wired headphones and playback time. Android only reports wired plug " +
                        "events to a running app, so this keeps a silent notification in the " +
                        "shade. Bluetooth is tracked either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!detailedSupported) {
                    Text(
                        "Unavailable until Bluetooth access is granted: this build only " +
                            "declares the connectedDevice service type, which Android 14+ ties " +
                            "to a Bluetooth permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = detailed,
                enabled = detailedSupported || detailed,
                onCheckedChange = { viewModel.setDetailedTracking(it) },
            )
        }
        HorizontalDivider()

        if (devices.isNotEmpty()) {
            SectionHeader("Devices")
            Text(
                "Uncheck anything that isn't headphones — a car stereo or a speaker — to stop " +
                    "recording it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setDeviceIgnored(device.id, !device.ignored) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = !device.ignored,
                        onCheckedChange = { viewModel.setDeviceIgnored(device.id, !it) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.defaultName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${device.kind.displayName()} · first seen ${formatDate(device.firstSeenAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }

        SectionHeader("Data")
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { exportLauncher.launch("ylih-backup.json") }) {
                Text("Export JSON")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                Text("Import")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Importing replaces everything currently stored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        SectionHeader("Troubleshooting")
        Text(
            "If sessions stop being recorded, check that ylih is exempt from battery " +
                "optimisation — an app that has been force-stopped never receives connection " +
                "broadcasts again until it is opened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (Distribution.HAS_BATTERY_SHORTCUT) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("Battery settings")
            }
        } else {
            Text(
                "Settings → Apps → ylih → Battery → Unrestricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        SectionHeader("About")
        Text(
            "ylih ${BuildConfig.VERSION_NAME} (${Distribution.ID}) — headphone hours, kept " +
                "locally and forever.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Replace all data?") },
            text = { Text("Importing this backup deletes every device, pair and session stored now.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFrom(uri)
                    confirmImport = null
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) { Text("Cancel") }
            },
        )
    }
}
