package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.eldavo.ylih.R

/**
 * Asked once, after the welcome, and only where the platform admits it hibernates apps.
 *
 * Hibernation is the one restriction that no amount of care in this app can work around: after a
 * few unopened months Android takes the Bluetooth permission back and the broadcasts stop, so the
 * pair that was quietly accumulating hours simply stops accumulating them. Nothing here can opt
 * out — only the user can, on a system screen — which is why this asks rather than fixes.
 *
 * Unlike the welcome, tapping outside dismisses it: by now there is a whole app behind it, and
 * "not now" is a real answer.
 */
@Composable
fun HibernationDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hibernation_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.hibernation_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings_hibernation_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}
