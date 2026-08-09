package it.eldavo.ylih.tracking

import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Whether the system may pause the app and revoke its permissions for going unused. */
enum class Hibernation {
    /** Android will hibernate ylih if it goes unopened for a few months. */
    ENABLED,

    /** The user has exempted ylih; it keeps its permissions however long it sits unopened. */
    DISABLED,

    /** Nothing to report: too old a platform, or the backport is not installed. */
    UNAVAILABLE,
}

/**
 * What the platform will admit about hibernation, and where the user can change it.
 *
 * It cannot be changed from code — the whole point of hibernation is that an app cannot opt
 * itself out — so the app's job here is to report accurately and hand over the screen that can.
 */
object Restrictions {

    /**
     * Asks whether unused-app restrictions are switched on for us. The answer arrives through a
     * future because on Android 6–10 it comes from a service in the Play services backport rather
     * than from the platform.
     */
    suspend fun hibernation(context: Context): Hibernation {
        val future = runCatching { PackageManagerCompat.getUnusedAppRestrictionsStatus(context) }
            .getOrNull() ?: return Hibernation.UNAVAILABLE
        val status = suspendCancellableCoroutine { continuation ->
            // Direct executor: the listener only reads an already-resolved value, and hopping to
            // the main thread to do it would be one more place for this to go wrong.
            future.addListener(
                {
                    val value = runCatching { future.get() }
                        .getOrDefault(UnusedAppRestrictionsConstants.ERROR)
                    continuation.resume(value)
                },
                Executor { it.run() },
            )
            continuation.invokeOnCancellation { future.cancel(false) }
        }
        // Only the two constants that mean "nothing to report" fall through to UNAVAILABLE.
        // Listing the enabled ones instead would silently stop the prompt appearing the day
        // androidx adds an API_33-shaped value, which is a `when` that rots by standing still.
        return when (status) {
            UnusedAppRestrictionsConstants.DISABLED -> Hibernation.DISABLED
            UnusedAppRestrictionsConstants.ERROR,
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
            -> Hibernation.UNAVAILABLE

            else -> Hibernation.ENABLED
        }
    }

    /**
     * The system screen where hibernation can be turned off, or null where there is none. Which
     * screen that is differs by platform version, which is the whole reason for [IntentCompat].
     */
    fun settingsIntent(context: Context): Intent? = runCatching {
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull()
}
