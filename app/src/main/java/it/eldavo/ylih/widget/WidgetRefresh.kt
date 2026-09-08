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
 * Called from `TrackingController` (background sources) and `YlihViewModel` (foreground edits) —
 * the two points every write converges on, since `SessionRepository`, the real funnel, holds no
 * `Context`.
 *
 * Failures are swallowed: reaching the launcher is an IPC to a process this app does not control.
 * A home screen that redraws late is cosmetic; a `syncWithSystem()` that threw on the way out is a
 * lost session.
 *
 * Cancellation is not swallowed — see [runCatchingCancellable]. It's the last thing `syncWithSystem`
 * does, so this changes nothing here, but is the wrong shape for a file meant to fail quietly.
 */
suspend fun refreshWidgets(context: Context) {
    runCatchingCancellable {
        LifetimeWidget().updateAll(context)
        ActivityWidget().updateAll(context)
        ChartWidget().updateAll(context)
        // Some figures move with the date, not the database, and no write announces a date, so
        // every refresh also arms [WidgetRolloverWorker]. It lives here, not in `TrackingController`,
        // which knows nothing about widgets beyond its callback.
        scheduleWidgetRollover(context)
    }.onFailure { Log.w(TAG, "Could not refresh the home-screen widgets", it) }
}

/**
 * Every widget this app ships, and the other half of [refreshWidgets] — without which a push does
 * nothing.
 *
 * `updateAll` does not re-run `provideGlance`. Glance runs that once to start a session — a
 * `SessionWorker` under WorkManager — keeping the composition alive about 45 seconds. An
 * `updateAll` inside that window only recomposes what's already there: its `UpdateGlanceState`
 * event re-reads the widget's own Glance state, nothing else. A widget that loads figures in
 * `provideGlance` and passes them to a composable holds what it read for the session's life, so
 * every push in that window redraws the same numbers.
 *
 * On the phone this read as updates going missing, not stale. Connecting headphones mid-session
 * changed the database, the app and the notification, pushed an accepted refresh, and left the home
 * screen unchanged. Detailed tracking looked like a timer: the service's minute tick outlasts the
 * 45-second session, so the tick was always the push finding no session, starting one, and reading
 * the database again — the connect showed up a minute late.
 *
 * Hence this class, not three corrected `provideGlance` bodies. [provideGlance] is `final`; a
 * subclass writes only [Content], and everything handed to [Content] arrives from a flow the
 * composition is collecting, so the mistake has nowhere to happen. `WidgetProvidersTest` holds
 * every manifest-named widget to this class, so a fourth can't arrive by another door.
 */
abstract class YlihWidget : GlanceAppWidget() {

    /** Not open — see [fits]: a fixed bucket kept the providers' resize ranges narrow. */
    final override val sizeMode = SizeMode.Exact

    final override suspend fun provideGlance(context: Context, id: GlanceId) {
        val updates = widgetContentFlow(context)
        // Read once before composing: `provideGlance` can take its time, the composition can't,
        // and a first frame drawn from nothing flashes empty every time the launcher asks.
        val initial = updates.first()
        provideContent {
            val (localized, data) = updates.collectAsState(initial).value
            Content(localized, data)
        }
    }

    /**
     * What this widget draws.
     *
     * [context] resolves the app's own language, not the system's; [data] is the current figures.
     * Both are re-delivered for the composition's life, so neither may be cached across a call.
     */
    @Composable
    @GlanceComposable
    abstract fun Content(context: Context, data: WidgetData)
}

private const val TAG = "WidgetRefresh"
