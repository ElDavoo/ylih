package it.eldavo.ylih.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll

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
 */
suspend fun refreshWidgets(context: Context) {
    runCatching {
        LifetimeWidget().updateAll(context)
        ActivityWidget().updateAll(context)
        ChartWidget().updateAll(context)
    }.onFailure { Log.w(TAG, "Could not refresh the home-screen widgets", it) }
}

private const val TAG = "WidgetRefresh"
