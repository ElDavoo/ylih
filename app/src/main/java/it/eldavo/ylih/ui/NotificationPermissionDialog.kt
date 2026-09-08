package it.eldavo.ylih.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import it.eldavo.ylih.R

/**
 * Asks for the notification permission the moment it matters: detailed tracking has just been
 * switched on, the only thing in the app with a notification to post.
 *
 * Raised on every switch-on rather than once: the answer is cheap to change and getting it wrong
 * is silent — a user who declined months ago, wondering why tracking shows no sign of running,
 * gets asked again next time. Android stops showing the system prompt after two refusals, so
 * this can't nag on its own — past that point the dialog is an explanation with a dead button,
 * so it states what the outcome means either way, not just what to tap.
 *
 * Detailed tracking is already on by the time this appears and stays on regardless of the
 * answer: without the permission the service still runs and records, Android just won't draw
 * its notification.
 *
 * @param permission the name to ask for, resolved by [notificationPermissionToAsk] so the API-33
 *   constant stays behind one SDK check.
 */
@Composable
internal fun NotificationPermissionDialog(
    permission: String,
    onDone: () -> Unit,
    onPermissionResult: () -> Unit = {},
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Granted, the service must be told: it's already running and posted its notification
        // with nowhere to put it, so nothing shows until something restarts it. syncWithSystem is
        // the entry point for this repair.
        onPermissionResult()
        onDone()
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.welcome_notifications_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PermissionRationale(
                    why = R.string.welcome_notifications_why,
                    withIt = R.string.welcome_notifications_with,
                    withoutIt = R.string.welcome_notifications_without,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { launcher.launch(permission) }) {
                Text(stringResource(R.string.welcome_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.action_not_now))
            }
        },
    )
}
