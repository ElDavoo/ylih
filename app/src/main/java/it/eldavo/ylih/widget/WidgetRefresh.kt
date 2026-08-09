package it.eldavo.ylih.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import it.eldavo.ylih.runCatchingCancellable
import kotlinx.coroutines.flow.first

/**
 * Redraws every placed widget.
 *
 * Called from the two points every write converges on — `TrackingController` for the background
 * sources and `YlihViewModel` for the foreground edits — because `SessionRepository`, which is the
 * real funnel, holds no `Context`.
 *
 * A failure here is swallowed on purpose. Reaching the launcher means an IPC to a process this app
 * does not control and cannot require to exist, and the callers are the app's own session writes
 * and its repair pass. A home screen that redraws a minute late is a cosmetic problem; a
 * `syncWithSystem()` that threw on the way out is a lost session.
 *
 * Cancellation is not one of those failures — see [runCatchingCancellable]. This is the last thing
 * `syncWithSystem` does, so swallowing it changed nothing here, but it is the wrong shape to leave
 * in a file whose whole job is to fail quietly.
 */
suspend fun refreshWidgets(context: Context) {
    runCatchingCancellable {
        LifetimeWidget().updateAll(context)
        ActivityWidget().updateAll(context)
        ChartWidget().updateAll(context)
    }.onFailure { Log.w(TAG, "Could not refresh the home-screen widgets", it) }
}

/**
 * Every widget this app ships, and the other half of [refreshWidgets] — the half without which a
 * push does nothing.
 *
 * `updateAll` does not re-run `provideGlance`. Glance runs that once to *start a session* — a
 * `SessionWorker` under WorkManager — and the session then keeps the composition alive for about 45
 * seconds. An `updateAll` arriving inside that window only recomposes what is already there: the
 * `UpdateGlanceState` event it sends re-reads the widget's own Glance state and nothing else. So a
 * widget that loads its figures in `provideGlance` and passes them to a composable holds whatever
 * it read for as long as the session lives, and every push landing in that window faithfully
 * redraws the numbers it already had.
 *
 * On the phone that read as updates going missing rather than as anything being stale. Connecting
 * headphones while a session was alive changed the database, the app and the notification, pushed a
 * refresh the launcher accepted — and left the home screen exactly as it was. Detailed tracking
 * made it look like a timer: the service's minute tick is longer than the 45 seconds a session
 * lives, so the tick was always the push that found no session running, had to start one, and
 * therefore read the database again. The connect itself showed up a minute late.
 *
 * Hence this class rather than three corrected `provideGlance` bodies. [provideGlance] is `final`
 * and a subclass is given no way to load anything: all it writes is [Content], and everything
 * [Content] is handed arrives from a flow the composition is collecting. The mistake is not one to
 * avoid — it is one there is nowhere left to make. `WidgetProvidersTest` holds every widget the
 * manifest names to this class, so a fourth cannot arrive by another door.
 */
abstract class YlihWidget : GlanceAppWidget() {

    /**
     * Not open: a widget composed for a fixed bucket leaves a band of empty background at every
     * size between two of them, which is what kept the providers' resize ranges narrow.
     */
    final override val sizeMode = SizeMode.Exact

    final override suspend fun provideGlance(context: Context, id: GlanceId) {
        val updates = widgetContentFlow(context)
        // Read once before composing, because `provideGlance` is the part of a session allowed to
        // take its time and the composition is not: a first frame drawn from nothing is a widget
        // that flashes empty every time the launcher asks for it.
        val initial = updates.first()
        provideContent {
            val (localized, data) = updates.collectAsState(initial).value
            Content(localized, data)
        }
    }

    /**
     * What this widget draws.
     *
     * [context] resolves the app's own language rather than the system's, and [data] is the current
     * figures — both re-delivered for as long as the composition lives, so neither may be cached
     * across a call.
     */
    @Composable
    @GlanceComposable
    abstract fun Content(context: Context, data: WidgetData)
}

private const val TAG = "WidgetRefresh"
