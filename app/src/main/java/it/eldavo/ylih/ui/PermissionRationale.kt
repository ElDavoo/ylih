package it.eldavo.ylih.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * The body of a permission explainer: what the permission is for, and what happens either way.
 *
 * Both outcomes get the same shape deliberately, so that declining reads as a choice with a known
 * result rather than as the option that quietly breaks something. The "with it" / "without it"
 * wording lives inside each string rather than being prefixed here, because a language that puts
 * it anywhere but the front needs it to.
 */
@Composable
internal fun PermissionRationale(
    @StringRes why: Int,
    @StringRes withIt: Int,
    @StringRes withoutIt: Int,
) {
    Text(stringResource(why), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(withIt), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(withoutIt),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * `POST_NOTIFICATIONS` if this install could still usefully be asked for it, and null otherwise.
 *
 * Returning the permission name rather than a boolean keeps every mention of an API-33 constant
 * behind the one SDK check on a minSdk-23 build, which is also the only shape lint accepts for it.
 */
internal fun notificationPermissionToAsk(context: Context): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !granted(context, Manifest.permission.POST_NOTIFICATIONS)
    ) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
