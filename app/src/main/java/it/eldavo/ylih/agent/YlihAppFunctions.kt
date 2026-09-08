package it.eldavo.ylih.agent

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.widget.widgetDataFlow
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * The questions an on-device agent may ask about this app's history.
 *
 * Read-only by construction: nothing an agent calls reaches a write, so `SessionRepository` — the
 * funnel every other source goes through — is not one of them. The figures come from
 * `widget/WidgetData.kt`, since a widget is the same problem as an agent (a caller that can't see
 * the UI and needs only the numbers) and that code is windowed, Glance-free and pinned by
 * `WidgetDataTest`. A third query path onto the same figures would be a third place for them to
 * disagree.
 *
 * KSP turns this into `YlihAppFunctionService` plus the schema XML in `assets/`; the manifest names
 * the generated class, which is why `app/src/main/keepRules/app-functions.keep` holds it to its own
 * name. The KDoc on each function below is not documentation for us — `isDescribedByKDoc = true`
 * makes it the text the agent reads to decide what the function does, so it's written for a caller
 * who can't see the code.
 *
 * Every function ships **disabled**. `isEnabled` is the compile-time default baked into that
 * schema, and false makes the settings switch an opt-in rather than a gesture: shipped enabled, the
 * functions would be callable in the window between install and first opening settings, and
 * disabling them at first run would race an agent that had already indexed the app.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
@AppFunctionServiceEntryPoint(
    serviceName = "YlihAppFunctionService",
    appFunctionXmlFileName = "ylih_app_functions",
)
abstract class YlihAppFunctions : AppFunctionService() {

    /**
     * Lists the user's pairs of headphones with the total hours each has been used for.
     *
     * The hours are a lifetime figure: this app records every connection from the day a pair is
     * first seen and never expires any of it, so the number answers "how long have these lasted"
     * rather than "how much lately". A pair the user has retired is left out of the list — it is a
     * pair they no longer own — but its hours are still part of the total.
     *
     * @return the pairs still in use, the one being worn right now first, and the lifetime total.
     */
    @AppFunction(isEnabled = false, isDescribedByKDoc = true)
    suspend fun getHeadphoneHours(): HeadphoneHours =
        headphoneHours(container(), ZoneId.systemDefault())

    /**
     * Reports how many hours the user has spent on headphones today, over the last seven days and
     * over the last thirty, alongside the lifetime total.
     *
     * Days are calendar days in the phone's own time zone, so "today" means since local midnight
     * rather than for the last twenty-four hours, and the seven- and thirty-day windows end now.
     * All four figures are the sum across every pair.
     *
     * @return the three windows and the lifetime total, and what the user asked to have counted.
     */
    @AppFunction(isEnabled = false, isDescribedByKDoc = true)
    suspend fun getListeningTotals(): ListeningTotals =
        listeningTotals(container(), ZoneId.systemDefault())

    private fun container() = (application as YlihApp).container
}

/*
 * The two answers, outside the service.
 *
 * Nothing that runs on a framework older than Android 17 can instantiate [YlihAppFunctions] — its
 * superclass isn't there to load — and the unit suite is exactly that, so a mapping written inside
 * the class is one no test can call. Out here it's ordinary code over an [AppContainer], which lets
 * `YlihAppFunctionsTest` assert that the hours an agent gets are the hours the app itself would
 * show. Same bargain as `WidgetData.kt`, for the same reason: the caller is a renderer nobody can
 * run from a test.
 */

internal suspend fun headphoneHours(container: AppContainer, zone: ZoneId): HeadphoneHours {
    val data = widgetDataFlow(container, zone).first()
    return HeadphoneHours(
        pairs = data.rows.map {
            HeadphonePair(
                name = it.label,
                hours = it.lifetimeMs.toHours(),
                inUseNow = it.openSince != null,
            )
        },
        lifetimeHours = data.totalMs.toHours(),
    )
}

internal suspend fun listeningTotals(container: AppContainer, zone: ZoneId): ListeningTotals {
    val data = widgetDataFlow(container, zone).first()
    return ListeningTotals(
        todayHours = data.todayMs.toHours(),
        lastSevenDaysHours = data.weekMs.toHours(),
        lastThirtyDaysHours = data.monthMs.toHours(),
        lifetimeHours = data.totalMs.toHours(),
        playbackOnly = data.counting == Counting.PLAYBACK,
    )
}

/**
 * Milliseconds as hours to one decimal — the same resolution `ui/Format.kt` shows on screen, so an
 * agent can't quote a figure the user wouldn't recognise from the app.
 */
private fun Long.toHours(): Double = (coerceAtLeast(0) / 360_000.0).roundToLong() / 10.0

/** A pair of headphones and what it has been used for, as an agent sees it. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class HeadphonePair(
    /** What the user calls this pair — the name they gave it, or the one the headphones report. */
    val name: String,
    /** Hours this pair has been used for since it was first seen, to the nearest tenth. */
    val hours: Double,
    /** True while this pair is connected to the phone right now. */
    val inUseNow: Boolean,
)

/** Every pair the user still owns, with the lifetime total across all of them. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class HeadphoneHours(
    /** The pairs still in use, the one connected right now first, then the longest-used. */
    val pairs: List<HeadphonePair>,
    /** Hours across every pair ever recorded, retired ones included, to the nearest tenth. */
    val lifetimeHours: Double,
)

/** How much the user has listened lately, and in total. */
@AppFunctionSerializable(isDescribedByKDoc = true)
class ListeningTotals(
    /** Hours since local midnight today, to the nearest tenth. */
    val todayHours: Double,
    /** Hours over the last seven days, to the nearest tenth. */
    val lastSevenDaysHours: Double,
    /** Hours over the last thirty days, to the nearest tenth. */
    val lastThirtyDaysHours: Double,
    /** Hours since the app started recording, to the nearest tenth. */
    val lifetimeHours: Double,
    /**
     * What the hours above count. False, the default, means time the headphones were connected;
     * true means only the time something was actually playing, which the user can ask for instead.
     */
    val playbackOnly: Boolean,
)

/**
 * Pushes the user's answer onto the OS index, where "off" has to be true, not just in the app's
 * own table.
 *
 * Called from `SettingsStore.setAgentAccess`, so the stored row and the platform state move
 * together rather than being two things a later caller could leave disagreeing.
 *
 * Below Android 17 there's no service to enable — the generated one is declared
 * `android:enabled="@bool/enablePlatformAppFunctionService"`, which the library resolves to false
 * there — so this no-ops and the stored row just waits for an OS with app functions.
 */
suspend fun pushAgentAccess(context: Context, enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
    val manager = AppFunctionManager.getInstance(context) ?: return
    val state = if (enabled) {
        AppFunctionManager.APP_FUNCTION_STATE_ENABLED
    } else {
        AppFunctionManager.APP_FUNCTION_STATE_DISABLED
    }
    // Named here, not at file scope, so the version check above covers them: the generated
    // companion belongs to a class that only exists on Android 17, and lint reads the guard.
    // Adding a function to [YlihAppFunctions] means adding it here too.
    val ids = listOf(
        YlihAppFunctionService.FUNCTION_ID_GET_HEADPHONE_HOURS,
        YlihAppFunctionService.FUNCTION_ID_GET_LISTENING_TOTALS,
    )
    for (id in ids) manager.setAppFunctionEnabled(id, state)
}
