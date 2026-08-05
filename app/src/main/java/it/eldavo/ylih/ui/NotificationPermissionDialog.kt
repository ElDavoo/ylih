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
 * Asks for the notification permission at the moment it starts to matter: detailed tracking has
 * just been switched on, and that is the only thing in the app with a notification to post.
 *
 * Deliberately raised on every switch-on rather than once, because the answer is cheap to change
 * and the cost of getting it wrong is silent — a user who declined months ago and has since
 * wondered why detailed tracking shows no sign of running gets asked again the next time they
 * turn it on. Android stops showing the system prompt after two refusals, so this cannot become
 * nagging on its own: past that point the dialog is an explanation with a button that does
 * nothing, which is why it says what the state means either way rather than only what to tap.
 *
 * Detailed tracking is already on by the time this appears, and stays on whatever the answer:
 * without the permission the service still runs and still records, Android just does not draw
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
        // Granted, the service has to be told: it is already running and posted its notification
        // while it had nowhere to put it, so nothing would show until something else restarted
        // it. syncWithSystem is the entry point for exactly this kind of repair.
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
