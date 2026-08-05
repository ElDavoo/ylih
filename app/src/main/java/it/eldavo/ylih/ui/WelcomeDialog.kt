package it.eldavo.ylih.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import it.eldavo.ylih.R

/**
 * One page of the first run.
 *
 * [permission] is what the page exists to ask for, and null on the intro, which asks for nothing.
 * Carrying it here rather than deriving it at the button keeps every mention of a permission
 * constant inside the one SDK-guarded place that decided the page was needed at all.
 */
internal data class WelcomePage(val kind: Kind, val permission: String? = null) {
    internal enum class Kind { INTRO, BLUETOOTH }
}

/**
 * The pages this install actually needs.
 *
 * Bluetooth gets a page where the platform has it to grant and the install does not already hold
 * it, rather than being shown and then sitting in front of a system prompt that never appears. So
 * the first run is one page below Android 12 and two above it.
 *
 * Notifications are deliberately not here. Nothing in the app reads that permission except the
 * foreground service's notification, and that service only exists once detailed tracking is
 * turned on — which most installs never do. Asking on the first run would be asking for something
 * the user may never need, in the one moment they have least context for answering, so the ask
 * lives on the switch that creates the need instead. See [SettingsScreen].
 */
internal fun welcomePages(context: Context): List<WelcomePage> = buildList {
    add(WelcomePage(WelcomePage.Kind.INTRO))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !granted(context, Manifest.permission.BLUETOOTH_CONNECT)
    ) {
        add(WelcomePage(WelcomePage.Kind.BLUETOOTH, Manifest.permission.BLUETOOTH_CONNECT))
    }
}

/**
 * First-run explainer, one page at a time.
 *
 * An app whose whole promise is a number years from now has to say so before it asks for
 * anything, or the permission prompt is the first thing a new install ever says. That is why the
 * intro comes first; it is also why Bluetooth gets a page of its own rather than arriving as a
 * system prompt behind a single "get started". A prompt with no reason attached is answered by
 * habit, and the answer to this one is the difference between the app working and the app
 * recording nothing for years.
 *
 * The permission page therefore says what the permission is for and what happens either way, and
 * raises the system prompt itself — so the reason is the last thing read before the choice is
 * made. Declining is a first-class answer: it advances like any other.
 *
 * @param onDismiss finishes the first run; the last page's button is what reaches it.
 * @param onPermissionResult a permission was answered, either way — the tracking machinery should
 *   look at the world again.
 */
@Composable
fun WelcomeDialog(onDismiss: () -> Unit, onPermissionResult: () -> Unit = {}) {
    val context = LocalContext.current
    // Decided once and then left alone. Recomputing would drop the Bluetooth page out of the list
    // the instant it was granted, taking the page the user is looking at with it.
    val pages = remember(context) { welcomePages(context) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages.getOrElse(index) { pages.first() }

    fun advance() {
        if (index >= pages.lastIndex) onDismiss() else index++
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Granted or denied, the page has said its piece and there is nothing to add: the system
        // refuses a second prompt for the same permission anyway, and the app explains the state
        // it ended up in from here on.
        onPermissionResult()
        advance()
    }

    AlertDialog(
        // Back steps back rather than dismissing, since the permission page is the whole point of
        // splitting the first run up and a stray back press should not skip past it. On the first
        // page there is nothing behind it to go to, which is the same reason a tap outside is
        // ignored.
        onDismissRequest = { if (index > 0) index-- },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Column {
                // Only worth saying where there is more than one page, which below Android 12
                // there is not. It is here so the permission page reads as part of something with
                // an end, rather than as an app that has started making demands.
                if (pages.size > 1) {
                    Text(
                        stringResource(R.string.welcome_step, index + 1, pages.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(stringResource(titleOf(page.kind)))
            }
        },
        text = {
            // Short on a phone, but a large display font plus a translation can overflow the
            // dialog's own bounds, which clips rather than scrolls.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (page.kind) {
                    WelcomePage.Kind.INTRO -> IntroPage()
                    WelcomePage.Kind.BLUETOOTH -> PermissionRationale(
                        why = R.string.welcome_bluetooth_why,
                        withIt = R.string.welcome_bluetooth_with,
                        withoutIt = R.string.welcome_bluetooth_without,
                    )
                }
            }
        },
        confirmButton = {
            val permission = page.permission
            if (permission == null) {
                TextButton(onClick = ::advance) {
                    Text(
                        stringResource(
                            if (index >= pages.lastIndex) R.string.welcome_start
                            else R.string.welcome_next,
                        ),
                    )
                }
            } else {
                TextButton(onClick = { launcher.launch(permission) }) {
                    Text(stringResource(R.string.welcome_allow))
                }
            }
        },
        dismissButton = {
            // Nothing to decline on the intro, so no second button there at all rather than one
            // that means the same as the first.
            if (page.permission != null) {
                TextButton(onClick = ::advance) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        },
    )
}

@StringRes
private fun titleOf(kind: WelcomePage.Kind): Int = when (kind) {
    WelcomePage.Kind.INTRO -> R.string.welcome_title
    WelcomePage.Kind.BLUETOOTH -> R.string.welcome_bluetooth_title
}

@Composable
private fun IntroPage() {
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
