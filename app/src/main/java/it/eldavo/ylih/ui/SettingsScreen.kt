package it.eldavo.ylih.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.BuildConfig
import it.eldavo.ylih.R
import it.eldavo.ylih.Distribution
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.tracking.Hibernation
import it.eldavo.ylih.tracking.Restrictions

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    viewModel: YlihViewModel,
    contentPadding: PaddingValues,
    scrollState: ScrollState = rememberScrollState(),
    onLanguageChanged: () -> Unit = rememberActivityRestart(),
) {
    val context = LocalContext.current
    val detailed by viewModel.detailedTracking.collectAsStateWithLifecycle()
    val counting by viewModel.counting.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickingLanguage by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<String?>(null) }
    // Non-null while the notification explainer is up, holding the permission it is about.
    var askNotifications by remember { mutableStateOf<String?>(null) }

    // Turning detailed tracking on is the moment the notification permission starts to mean
    // anything: it is what creates the foreground service, and that service's notification is the
    // only thing in the app that permission governs. Asked here rather than on the first run, so
    // the reason is in front of the user instead of months behind them.
    //
    // The setting is written either way and does not wait for an answer — the service runs and
    // records without the permission, Android simply does not draw its notification.
    fun setDetailed(enabled: Boolean) {
        viewModel.setDetailedTracking(enabled)
        if (enabled) askNotifications = notificationPermissionToAsk(context)
    }

    // The write goes through the settings table and the new configuration is only readable once
    // it has landed: restarting any earlier reattaches the activity with the language it already had.
    LaunchedEffect(pendingLanguage, language) {
        if (pendingLanguage != null && pendingLanguage == language) {
            pendingLanguage = null
            onLanguageChanged()
        }
    }

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
        val detailedSupported by viewModel.detailedTrackingSupported.collectAsStateWithLifecycle()
        SectionHeader(stringResource(R.string.settings_tracking))
        Row(
            // toggleable rather than clickable, with the switch along for the ride — the same
            // shape LanguageRow below already uses, and for the same reason: one click target and
            // one thing for a screen reader to announce, with its state, rather than two.
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = detailed,
                    enabled = detailedSupported || detailed,
                    role = Role.Switch,
                    onValueChange = ::setDetailed,
                )
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
                onCheckedChange = null,
            )
        }
        // Playback is only ever measured by the foreground service, so on a build that cannot run
        // it the choice would be between real hours and a column of zeroes. Offering it once the
        // service is possible, rather than once it is running, keeps history recorded earlier
        // readable after detailed tracking is switched back off.
        if (detailedSupported || detailed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = counting == Counting.PLAYBACK,
                        role = Role.Switch,
                        onValueChange = viewModel::setPlaybackOnly,
                    )
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_playback_only_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_playback_only_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = counting == Counting.PLAYBACK,
                    onCheckedChange = null,
                )
            }
        }
        HorizontalDivider()

        // Android 13 took per-app language over: the generated locale config puts ylih in
        // Settings > System > Languages there, and a second switch inside the app would be a
        // second answer to the same question. Below 13 there is no such setting to defer to.
        if (AppLocale.NEEDS_IN_APP_PICKER) {
            SectionHeader(stringResource(R.string.settings_language))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickingLanguage = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(languageLabel(language), style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }

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
                        .toggleable(
                            value = !device.ignored,
                            role = Role.Checkbox,
                            onValueChange = { viewModel.setDeviceIgnored(device.id, !it) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = !device.ignored, onCheckedChange = null)
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

        // Read once per composition rather than observed: hibernation only ever changes on the
        // system screen the button below opens, which takes the activity away first.
        val hibernation by produceState(Hibernation.UNAVAILABLE, context) {
            value = Restrictions.hibernation(context)
        }
        if (hibernation != Hibernation.UNAVAILABLE) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.settings_hibernation_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (hibernation == Hibernation.ENABLED) {
                        R.string.settings_hibernation_on
                    } else {
                        R.string.settings_hibernation_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Still offered once exempt: the exemption is the user's to withdraw, and a button
            // that vanishes on success leaves no way back to the screen that set it.
            // Remembered: this resolves an activity through PackageManager, and YlihNavHost's
            // copy of the same call already knew to.
            remember(context) { Restrictions.settingsIntent(context) }?.let { intent ->
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { context.startActivity(intent) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_hibernation_button))
                }
            }
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

    if (pickingLanguage) {
        LanguageDialog(
            current = language.orEmpty(),
            onPick = { tag ->
                pickingLanguage = false
                if (tag != language) {
                    pendingLanguage = tag
                    viewModel.setLanguage(tag)
                }
            },
            onDismiss = { pickingLanguage = false },
        )
    }

    askNotifications?.let { permission ->
        NotificationPermissionDialog(
            permission = permission,
            onDone = { askNotifications = null },
            // The service is already up and posted its notification while it had nowhere to put
            // it, so a grant shows nothing until something restarts it.
            onPermissionResult = viewModel::syncWithSystem,
        )
    }
}

/**
 * Applying a language means rebuilding every context that was created under the old one, and the
 * activity is the only one alive by then. Hoisted into a parameter so a test can watch for the
 * restart rather than have its own activity torn down mid-composition.
 */
@Composable
fun rememberActivityRestart(): () -> Unit {
    val activity = LocalActivity.current
    return remember(activity) { { activity?.recreate() } }
}

@Composable
private fun languageLabel(tag: String?): String =
    if (tag.isNullOrEmpty()) {
        stringResource(R.string.settings_language_system)
    } else {
        AppLocale.displayName(tag)
    }

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tags = remember(context) { AppLocale.pickerTags(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            // 77 translations and counting, so the list is lazy and scrolls inside the dialog.
            LazyColumn {
                item {
                    LanguageRow(
                        label = stringResource(R.string.settings_language_system),
                        selected = current == AppLocale.SYSTEM,
                        onClick = { onPick(AppLocale.SYSTEM) },
                    )
                }
                items(tags) { tag ->
                    LanguageRow(
                        label = AppLocale.displayName(tag),
                        selected = current == tag,
                        onClick = { onPick(tag) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // selectable rather than clickable, with the radio button along for the ride: one
            // click target and one thing for a screen reader to announce, not two.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // A RadioButton with a null onClick brings no minimum touch target of its own, which
            // left these rows about 32dp tall — in a dialog listing seventy-seven of them.
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
