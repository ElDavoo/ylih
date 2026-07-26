package it.eldavo.ylih.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import it.eldavo.ylih.R

/**
 * First-run explainer. An app whose whole promise is a number years from now has to say so before
 * it asks for Bluetooth, or the permission prompt is the first thing a new install ever says.
 *
 * Tapping outside does not dismiss it: this is shown exactly once, and there is nothing behind it
 * yet to tap towards.
 */
@Composable
fun WelcomeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.welcome_title)) },
        text = {
            // Short on a phone, but a large display font plus a translation can overflow the
            // dialog's own bounds, which clips rather than scrolls.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.welcome_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                WelcomePoint(
                    title = stringResource(R.string.welcome_point_auto_title),
                    body = stringResource(R.string.welcome_point_auto_body),
                )
                WelcomePoint(
                    title = stringResource(R.string.welcome_point_lifetime_title),
                    body = stringResource(R.string.welcome_point_lifetime_body),
                )
                WelcomePoint(
                    title = stringResource(R.string.welcome_point_detailed_title),
                    body = stringResource(R.string.welcome_point_detailed_body),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.welcome_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.welcome_start))
            }
        },
    )
}

@Composable
private fun WelcomePoint(title: String, body: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
