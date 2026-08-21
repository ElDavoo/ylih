package it.eldavo.ylih.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import it.eldavo.ylih.YlihApp
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The one thing a pushed refresh cannot do: notice that the date changed.
 *
 * `todayMs`, the two window totals and the chart's series are all bucketed by local midnight, and
 * nothing writes to the database when a day ends — so without this the home screen kept yesterday's
 * "today" until the next connect. See [WidgetRolloverWorker] for the whole story.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetRolloverTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.of("Europe/Rome")

    @Before
    fun setUp() {
        // scheduleWidgetRollover reaches WorkManager, whose androidx.startup initializer never
        // runs here — the same reason TrackingServiceTest does this.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private var nextWidgetId = 1

    /**
     * Puts a widget on the fake home screen, the way the launcher does.
     *
     * `bindAppWidgetId` rather than the shadow's `createWidget`, which also *delivers* an
     * APPWIDGET_UPDATE to the receiver — and Glance's receiver answers that with `goAsync()`, which
     * on a broadcast Robolectric synthesised has no `PendingResult` to finish. The NPE that follows
     * lands on a coroutine thread, so it fails whichever test happens to start next rather than this
     * one. Binding is all this needs: the question under test is what the platform's widget list
     * says, not what a receiver does when poked.
     */
    private fun place(provider: Class<out AppWidgetProvider>) {
        shadowOf(AppWidgetManager.getInstance(app))
            .bindAppWidgetId(nextWidgetId++, ComponentName(app, provider))
    }

    private fun scheduled(): WorkInfo? = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork(WidgetRolloverWorker.NAME)
        .get()
        .firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    @Test
    fun `a home screen with no widgets on it schedules no wakeups`() = runTest {
        scheduleWidgetRollover(app, zone = zone)

        assertEquals("nothing reads a date, so nothing needs waking", null, scheduled())
    }

    @Test
    fun `an activity widget arms the rollover`() = runTest {
        place(ActivityWidgetReceiver::class.java)

        scheduleWidgetRollover(app, zone = zone)

        assertTrue("the widget shows today's hours and nothing else would correct them", scheduled() != null)
    }

    @Test
    fun `a chart widget arms it too`() = runTest {
        place(ChartWidgetReceiver::class.java)

        scheduleWidgetRollover(app, zone = zone)

        assertTrue("its last bar is today", scheduled() != null)
    }

    @Test
    fun `the lifetime widget alone arms nothing`() = runTest {
        place(LifetimeWidgetReceiver::class.java)

        scheduleWidgetRollover(app, zone = zone)

        // Lifetime hours and a Chronometer: neither knows what day it is, so a daily wakeup would
        // be a battery cost bought for nothing.
        assertEquals(null, scheduled())
    }

    @Test
    fun `it is armed for the next local midnight`() = runTest {
        place(ActivityWidgetReceiver::class.java)

        scheduleWidgetRollover(app, zone = zone)

        val expected = nextLocalMidnight(app.container.clock.now(), zone)
        val actual = checkNotNull(scheduled()).nextScheduleTimeMillis
        // A minute past the hour rather than on it: a delivery a hair early would bucket the day
        // that has just ended and then schedule its successor for an instant already gone.
        assertTrue(
            "$actual is not the minute after $expected",
            actual > expected && actual <= expected + 120_000,
        )
    }

    @Test
    fun `a schedule outliving the widget that wanted it is cancelled`() = runTest {
        // Enqueued by hand because Robolectric's shadow can place a widget but not take one away,
        // and this is the state that matters: the daily wakeup surviving the widget it was armed
        // for would be a battery cost nothing on the home screen could ever spend.
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WidgetRolloverWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WidgetRolloverWorker>(1, TimeUnit.DAYS)
                // Waiting for a midnight that has not come, as the real one always is. Without a
                // delay the test harness's synchronous executor runs it on the spot and there is
                // nothing left enqueued for the cancel to be about.
                .setInitialDelay(1, TimeUnit.DAYS)
                .build(),
        )
        assertTrue(scheduled() != null)

        scheduleWidgetRollover(app, zone = zone)

        assertEquals("a widget nobody has placed is nothing to wake up for", null, scheduled())
    }

    @Test
    fun `the run redraws and re-arms itself for the following midnight`() = runTest {
        place(ActivityWidgetReceiver::class.java)
        // Nothing scheduled going in, so what comes out can only have been armed by the run
        // itself. `TestListenableWorkerBuilder` calls `doWork` directly rather than through
        // WorkManager, so a schedule left over from before would still be sitting there enqueued
        // and this would pass on a worker that did nothing at all.
        assertEquals(null, scheduled())

        val result = TestListenableWorkerBuilder<WidgetRolloverWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Re-armed rather than left to drift: WorkManager measures the next period from the end of
        // the last run, so however late this one was delivered would be added to every day after
        // it. UPDATE is the one policy that re-times work without cancelling the run calling it.
        val next = checkNotNull(scheduled()) { "the chain ended with this run" }.nextScheduleTimeMillis
        // systemDefault(), not `zone`: doWork() re-arms through scheduleWidgetRollover with no zone
        // argument, so it is the JVM default it actually reschedules against. Comparing against the
        // fixed Rome zone the other tests use made this flake for the daily window between Rome's
        // local midnight and the default zone's, where the two "next midnight"s invert.
        assertTrue(
            "$next is not the coming midnight",
            next > nextLocalMidnight(app.container.clock.now(), ZoneId.systemDefault()),
        )
    }

    @Test
    fun `a scheduling failure is retried rather than left unscheduled`() = runTest {
        place(ActivityWidgetReceiver::class.java)
        // scheduleWidgetRollover's own failures are the ones doWork()'s catch block exists for —
        // see HeartbeatWorker for the same shape — but nothing reaches it from outside WorkManager's
        // internals: closing WorkManagerTestInitHelper's database does not propagate synchronously,
        // so the top-level function is mocked to throw in its place.
        mockkStatic(::scheduleWidgetRollover)
        coEvery { scheduleWidgetRollover(any(), ExistingPeriodicWorkPolicy.UPDATE, any()) } throws
            IllegalStateException("Room reports a transaction it could not start")

        val result = TestListenableWorkerBuilder<WidgetRolloverWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @After
    fun tearDown() {
        unmockkStatic(::scheduleWidgetRollover)
    }

    @Test
    fun `re-arming an existing schedule is cheap enough to do on every refresh`() = runTest {
        place(ActivityWidgetReceiver::class.java)
        scheduleWidgetRollover(app, zone = zone)
        val first = checkNotNull(scheduled()).id

        // KEEP, so the service's minute tick does not rewrite WorkManager's database sixty times
        // an hour for a schedule that has not moved.
        scheduleWidgetRollover(app, ExistingPeriodicWorkPolicy.KEEP, zone)

        assertEquals("the same enqueued work, not a replacement", first, checkNotNull(scheduled()).id)
    }
}
