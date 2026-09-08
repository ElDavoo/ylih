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
 * Refreshes are otherwise pushed, never polled — fine for everything a write can change. But
 * `todayMs`, `weekMs`, `monthMs` and the chart's series bucket by *local midnight*, so they also
 * change when the **date** does, and no write announces that. Crossing midnight with nothing
 * connected reaches neither push trigger, so `ylih · activity` kept "today" showing yesterday's
 * hours and `ylih · 30 days` kept a chart whose last bar was yesterday, until the next connect. The
 * app's own screens re-derive from `nowMinute` and keep ticking; a launcher keeps the last
 * `RemoteViews` it was given, so a widget has nothing that ticks.
 *
 * Different from what [YlihWidget] solves: that is a push not reaching a live composition; this is
 * no push existing at all.
 *
 * Not the same as `HeartbeatWorker`: nothing here touches a session, so lateness costs a stale
 * figure, not a lost hour. Runs daily, only while a widget with a dated figure is on the home
 * screen.
 */
class WidgetRolloverWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        refreshWidgets(applicationContext)
        // Re-pin the *following* midnight. WorkManager measures the next period from the end of the
        // last run, so however late delivery was gets added to every day after it: a job starting
        // at 00:00 slides into the afternoon over a few months, leaving the morning stale.
        scheduleWidgetRollover(applicationContext, ExistingPeriodicWorkPolicy.UPDATE)
        Result.success()
    } catch (e: Exception) {
        // See HeartbeatWorker: Room reports a failed transaction start as a cancellation, so a
        // cancellation here is a failure in disguise.
        currentCoroutineContext().ensureActive()
        // Retry rather than fail: a failed run leaves nothing scheduled, and only a database write
        // would reschedule one — exactly what a stale day lacks.
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
 * [policy] is [ExistingPeriodicWorkPolicy.KEEP] for ordinary callers — every widget refresh the app
 * makes — so an existing schedule is untouched and the call costs a lookup, not a WorkManager write;
 * that matters since the service's tick reaches here once a minute. The worker itself passes
 * [ExistingPeriodicWorkPolicy.UPDATE], the one policy that re-times work in place without cancelling
 * the run making the call.
 *
 * The period is a day plus an exact-instant override, not an initial delay on one-shot work:
 * one-shot work can't re-arm itself. Enqueued under its own unique name from inside its own run,
 * KEEP would drop the request as a duplicate and REPLACE would cancel the run halfway through.
 *
 * The lifetime widget is skipped: it shows lifetime hours and a live `Chronometer`, which don't
 * know the date.
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
            // A minute past, not on the stroke: figures come from the clock the run itself sees,
            // and a delivery a hair early would bucket the day just ended and schedule its
            // successor for an instant already gone.
            .setNextScheduleTimeOverride(nextLocalMidnight(now, zone) + ROLLOVER_MARGIN_MS)
            .build(),
    )
}

/**
 * Whether any placed widget shows a figure that a change of date alone would make wrong.
 *
 * Asked of `AppWidgetManager` rather than `GlanceAppWidgetManager`, which answers from a map it
 * fills in only once a widget has composed. On a fresh boot, before the launcher asks for anything,
 * Glance would say no widgets are placed and cancel the schedule it exists to keep. The platform's
 * own list, maintained by the launcher, is right from the first moment.
 */
private fun showsADatedFigure(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context) ?: return false
    return DATED_WIDGETS.any {
        manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
    }
}

/**
 * The receivers whose widgets read a date. [LifetimeWidgetReceiver] is not one: it shows lifetime
 * hours and a live `Chronometer`, and neither knows what day it is.
 */
private val DATED_WIDGETS = listOf(
    ActivityWidgetReceiver::class.java,
    ChartWidgetReceiver::class.java,
)

private const val ROLLOVER_MARGIN_MS = 60_000L
