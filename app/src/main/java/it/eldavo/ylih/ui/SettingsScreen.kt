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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.BuildConfig
import it.eldavo.ylih.R
import it.eldavo.ylih.Distribution

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    viewModel: YlihViewModel,
    contentPadding: PaddingValues,
    scrollState: ScrollState = rememberScrollState(),
) {
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
            .verticalScroll(scrollState)
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
    ) {
        val detailedSupported = viewModel.detailedTrackingSupported()
        SectionHeader(stringResource(R.string.settings_tracking))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setDetailedTracking(!detailed) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_detailed_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.settings_detailed_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!detailedSupported) {
                    Text(
                        stringResource(R.string.settings_detailed_unavailable),
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
            SectionHeader(stringResource(R.string.settings_devices))
            Text(
                stringResource(R.string.settings_devices_body),
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
                            stringResource(
                                R.string.settings_device_subtitle,
                                device.kind.displayName(),
                                formatDate(device.firstSeenAt),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }

        SectionHeader(stringResource(R.string.settings_data))
        // ButtonGroupScope is not a composable scope, so the labels are resolved out here.
        val exportLabel = stringResource(R.string.settings_export)
        val importLabel = stringResource(R.string.settings_import)
        // A ButtonGroup rather than two loose buttons: these are one choice with two answers, and
        // Expressive's group squashes its neighbours as you press, which reads as exactly that.
        ButtonGroup(
            modifier = Modifier.padding(horizontal = 16.dp),
            overflowIndicator = {},
        ) {
            clickableItem(
                onClick = { exportLauncher.launch("ylih-backup.json") },
                label = exportLabel,
            )
            clickableItem(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                label = importLabel,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_import_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_troubleshooting))
        Text(
            stringResource(R.string.settings_battery_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (Distribution.HAS_BATTERY_SHORTCUT) {
            // Filled, like the export/import group above: ButtonGroup's items are always a filled
            // Button, so an outlined one here read as a different kind of control rather than a
            // lower-priority one.
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(stringResource(R.string.settings_battery_button))
            }
        } else {
            Text(
                stringResource(R.string.settings_battery_path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_about))
        Text(
            stringResource(
                R.string.settings_about_body,
                BuildConfig.VERSION_NAME,
                Distribution.ID,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFrom(uri)
                    confirmImport = null
                }) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
