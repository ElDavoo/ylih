package it.eldavo.ylih.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Redraws the widgets whose figures move with the calendar rather than with the database.
 *
 * Refreshes are otherwise pushed and never polled, and that is right for everything a write can
 * change. But `todayMs`, `weekMs`, `monthMs` and the chart's series are bucketed by *local
 * midnight*, so they also change when the **date** does — and no write announces that. Crossing
 * midnight with nothing connected reaches neither of the two points a push comes from, so
 * `ylih · activity` went on showing yesterday's hours under "today" and `ylih · 30 days` went on
 * drawing a chart whose last bar was yesterday, until the first connect of the new day. The app's
 * own screens never had this: they re-derive from `nowMinute`, which keeps ticking. A launcher
 * keeps the last `RemoteViews` it was given, so a widget has nothing that ticks.
 *
 * This is a different problem from the one [YlihWidget] solves. That one is a push not reaching a
 * live composition; this one is no push existing at all.
 *
 * Deliberately not the same thing as `HeartbeatWorker`: nothing here touches a session, and being
 * late costs a stale figure rather than a lost hour. It runs once a day, and only while a widget
 * that shows a dated figure is actually on the home screen.
 */
class WidgetRolloverWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        refreshWidgets(applicationContext)
        // Re-pin the *following* midnight. Without this the schedule drifts: WorkManager measures
        // the next period from the end of the last run, so however late the system chose to
        // deliver this one is added to every day after it, and a job that starts at 00:00 slides
        // into the afternoon over a few months — where the whole morning shows yesterday's
        // figures. Re-stating the exact time once a day is what keeps the drift from accumulating.
        scheduleWidgetRollover(applicationContext, ExistingPeriodicWorkPolicy.UPDATE)
        Result.success()
    } catch (e: Exception) {
        // See HeartbeatWorker: Room reports a transaction it could not start by cancelling, so a
        // cancellation with this job still active is a failure wearing the wrong clothes.
        currentCoroutineContext().ensureActive()
        // Retry rather than fail: a failed run leaves nothing scheduled, and the next thing that
        // would schedule one is a database write — which is exactly what a stale day has none of.
        Log.w(TAG, "Widget rollover failed", e)
        Result.retry()
    }

    companion object {
        const val NAME = "ylih-widget-rollover"

        private const val TAG = "WidgetRolloverWorker"
    }
}

/**
 * Arms the rollover for the next local midnight, or cancels it where nothing would read it.
 *
 * [policy] is [ExistingPeriodicWorkPolicy.KEEP] for the ordinary callers, which are every widget
 * refresh the app makes: an existing schedule is then left alone and the call costs a lookup rather
 * than a write to WorkManager's own database, which matters because the service's tick reaches here
 * once a minute. The worker itself passes [ExistingPeriodicWorkPolicy.UPDATE], which is the one
 * policy that re-times work in place without cancelling the run making the call.
 *
 * The period is a day and the exact instant is an override on top of it, rather than an initial
 * delay on one-shot work, because one-shot work cannot re-arm itself: enqueued under its own unique
 * name from inside its own run, KEEP drops the request as a duplicate of the run making it and
 * REPLACE cancels that run halfway through.
 *
 * The lifetime widget is not consulted. It shows lifetime hours and a live `Chronometer`, neither
 * of which knows what day it is.
 */
suspend fun scheduleWidgetRollover(
    context: Context,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val workManager = WorkManager.getInstance(context)
    if (!showsADatedFigure(context)) {
        // Nothing on the home screen reads a date, so there is nothing for a daily wakeup to fix.
        workManager.cancelUniqueWork(WidgetRolloverWorker.NAME)
        return
    }
    val now = (context.applicationContext as YlihApp).container.clock.now()
    workManager.enqueueUniquePeriodicWork(
        WidgetRolloverWorker.NAME,
        policy,
        PeriodicWorkRequestBuilder<WidgetRolloverWorker>(1, TimeUnit.DAYS)
            // A minute past, not on the stroke: the figures are read from the clock the run itself
            // sees, and a delivery a hair early would bucket the day that has just ended and then
            // schedule its successor for an instant already gone.
            .setNextScheduleTimeOverride(nextLocalMidnight(now, zone) + ROLLOVER_MARGIN_MS)
            .build(),
    )
}

/**
 * Whether any placed widget shows a figure that a change of date alone would make wrong.
 *
 * Asked of `AppWidgetManager` rather than of `GlanceAppWidgetManager`, which answers the same
 * question from a map of its own that it only fills in once a widget has actually composed — so on
 * a fresh boot, before the launcher has asked for anything, Glance says no widgets are placed and
 * this would cancel the very schedule it is here to keep. The platform's own list is the one the
 * launcher maintains and is right from the first moment.
 */
private fun showsADatedFigure(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context) ?: return false
    return DATED_WIDGETS.any {
        manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
    }
}

/**
 * The receivers whose widgets read a date. [LifetimeWidgetReceiver] is deliberately not one: it
 * shows lifetime hours and a live `Chronometer`, and neither knows what day it is.
 */
private val DATED_WIDGETS = listOf(
    ActivityWidgetReceiver::class.java,
    ChartWidgetReceiver::class.java,
)

private const val ROLLOVER_MARGIN_MS = 60_000L
