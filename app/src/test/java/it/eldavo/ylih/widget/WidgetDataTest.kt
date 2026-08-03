package it.eldavo.ylih.widget

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.AppLocale
import it.eldavo.ylih.YlihApp
import it.eldavo.ylih.data.AppContainer
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.stats.Counting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the home-screen widgets are allowed to say.
 *
 * Rendered widget output only exists inside a launcher, so everything worth pinning was kept out
 * of Glance and lives in `WidgetData.kt`; this is the test that holds it. The rules it protects
 * are the ones a wrong widget would break loudest: a connected pair has to be visible first, a
 * retired pair must not vanish from the lifetime total, playback-only mode has to reach the home
 * screen too, and the 30-day window must not quietly drop a session that is still running.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class WidgetDataTest {

    private val app: YlihApp = ApplicationProvider.getApplicationContext()

    // Fixed zone and wall time: every figure here is bucketed by local midnight, so a test that
    // borrowed the machine's zone would pass or fail depending on where it ran.
    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val today: LocalDate = LocalDate.of(2026, 3, 18)
    private var clockNow = at(15, 0)

    private lateinit var container: AppContainer

    private val hour = 3_600_000L
    private val day = 24 * hour

    @Before
    fun setUp() = runTest {
        // Its own container rather than the app's, because only an injected clock makes any of
        // this repeatable. The database and the settings underneath are still the real ones, and
        // Robolectric hands them on between test methods, so both are cleared here.
        container = AppContainer(app) { clockNow }
        container.database.deviceDao().deleteAll()
        container.settings.setPlaybackOnly(false)
    }

    @Test
    fun `the connected pair leads the list, however few hours it has`() = runTest {
        val veteran = seedPair("Sennheiser HD 25")
        seedSession(veteran, from = clockNow - 200 * day, to = clockNow - 200 * day + 100 * hour)
        val newcomer = seedPair("Galaxy Buds3 Pro")
        seedSession(newcomer, from = clockNow - 2 * hour, to = null)

        val rows = load().rows

        assertEquals(
            "the pair being worn right now is the one the widget is for",
            listOf("Galaxy Buds3 Pro", "Sennheiser HD 25"),
            rows.map { it.label },
        )
        assertEquals(clockNow - 2 * hour, rows.first().openSince)
        assertEquals("and it is counted live, not from its closed sessions", 2 * hour, rows.first().lifetimeMs)
        assertNull(rows.last().openSince)
    }

    @Test
    fun `a retired pair leaves the list but not the grand total`() = runTest {
        val retired = seedPair("Sony WH-1000XM4", retiredAt = clockNow - 10 * day)
        seedSession(retired, from = clockNow - 100 * day, to = clockNow - 100 * day + 50 * hour)
        val current = seedPair("Sony WH-1000XM5")
        seedSession(current, from = clockNow - 2 * day, to = clockNow - 2 * day + 4 * hour)

        val data = load()

        assertEquals(
            "a frozen pair would crowd the live ones out of a widget three rows tall",
            listOf("Sony WH-1000XM5"),
            data.rows.map { it.label },
        )
        assertEquals("but the hours still happened", 54 * hour, data.totalMs)
    }

    @Test
    fun `playback-only mode reaches the home screen too`() = runTest {
        val pair = seedPair("Galaxy Buds3 Pro")
        // Entirely inside today, so the day bucketing cannot be what makes this pass.
        seedSession(pair, from = at(5, 0), to = at(15, 0), playingMs = 3 * hour)

        val connected = load()
        assertEquals(Counting.CONNECTED, connected.counting)
        assertEquals(10 * hour, connected.totalMs)
        assertEquals(10 * hour, connected.todayMs)
        assertEquals(10 * hour, connected.rows.single().lifetimeMs)

        container.settings.setPlaybackOnly(true)

        val playback = load()
        assertEquals(Counting.PLAYBACK, playback.counting)
        assertEquals(
            "a home screen showing a different number from the app it came from is the worst of both",
            3 * hour,
            playback.totalMs,
        )
        assertEquals(3 * hour, playback.todayMs)
        assertEquals(3 * hour, playback.rows.single().lifetimeMs)
    }

    @Test
    fun `the window reads what it can reach and no more`() = runTest {
        val pair = seedPair("Sennheiser HD 25")
        val ancient = seedSession(pair, from = clockNow - 40 * day, to = clockNow - 40 * day + 5 * hour)
        val recent = seedSession(pair, from = clockNow - 3 * day, to = clockNow - 3 * day + 2 * hour)
        // Started before the window and never closed: the reason the query cannot simply compare
        // connectedAt, and the case a widget gets wrong by showing nothing while a pair is on.
        val stillOn = seedPair("Galaxy Buds3 Pro")
        val running = seedSession(stillOn, from = clockNow - 60 * day, to = null)

        val reachable = container.repository.sessionsSince(windowStart(clockNow, zone)).map { it.id }
        assertEquals(setOf(recent, running), reachable.toSet())
        assertTrue("a session that began and ended before the window is dead weight", ancient !in reachable)

        val data = load()
        assertEquals(
            "the 2 h that landed inside the window, plus every hour the open session has run " +
                "inside it — the 5 h before the window is the only thing missing",
            2 * hour + (clockNow - windowStart(clockNow, zone)),
            data.monthMs,
        )
        assertEquals("while lifetime keeps all 7 h", 7 * hour, data.rows.first { it.label == "Sennheiser HD 25" }.lifetimeMs)
    }

    @Test
    fun `an empty database still fills the chart`() = runTest {
        val data = load()

        assertEquals(emptyList<WidgetRow>(), data.rows)
        assertEquals(0L, data.totalMs)
        assertEquals(0L, data.todayMs)
        assertEquals(0L, data.weekMs)
        assertEquals(0L, data.monthMs)
        assertEquals(WIDGET_DAYS, data.series.size)
        assertEquals("today is the right-hand bar, always", today, data.series.last().first)
        assertEquals(today.minusDays((WIDGET_DAYS - 1).toLong()), data.series.first().first)
        assertTrue("a zero-filled chart, not an empty one", data.series.all { it.second == 0L })
    }

    @Test
    fun `left to itself it uses the phone's own zone`() = runTest {
        // Every widget calls it this way; the zone argument exists so the rest of this class can
        // stop depending on where it runs.
        val pair = seedPair("Galaxy Buds3 Pro")
        seedSession(pair, from = clockNow - 2 * hour, to = null)

        val data = loadWidgetData(container)

        assertEquals(2 * hour, data.rows.single().lifetimeMs)
        assertEquals(WIDGET_DAYS, data.series.size)
    }

    @Test
    fun `a widget reads the app's own container`() = runTest {
        // What provideGlance calls, and the only part of it a test can reach. A broadcast-woken
        // process has nothing but the Application to find the database through, so reaching it
        // through YlihApp rather than through anything Glance hands in is the whole point.
        val (_, data) = widgetContentFlow(app).first()

        assertEquals(WIDGET_DAYS, data.series.size)
        assertEquals(app.container.clock.now() / 1000, data.now / 1000)
    }

    @Test
    fun `a widget's figures keep arriving after it has been composed`() = runBlocking {
        // The one thing a widget cannot do is read its figures once. Glance runs provideGlance to
        // *start a session* and then keeps that composition alive for about 45 seconds; an
        // updateAll arriving inside that window recomposes what is already there rather than
        // loading it again. Figures passed in as a parameter therefore cannot change while a
        // session lives, so every push that landed in one faithfully redrew the numbers it already
        // had — which is how connecting headphones reached the home screen a minute late, on the
        // service tick that was finally late enough to find no session running and have to start
        // another. Real threads and real time, because Room's invalidation is what delivers the
        // second reading and it is not on this test's dispatcher.
        val pair = seedPair("Galaxy Buds3 Pro")
        val seen = Channel<WidgetData>(Channel.UNLIMITED)
        val collecting = launch(Dispatchers.IO) { widgetDataFlow(container, zone).collect(seen::send) }
        try {
            assertNull("nothing was connected when it was composed", next(seen).rows.single().openSince)
            seedSession(pair, from = clockNow, to = null)
            assertEquals(
                "and the connect reaches it without a second provideGlance",
                clockNow,
                next(seen).rows.single().openSince,
            )
        } finally {
            collecting.cancel()
        }
    }

    @Test
    fun `the window starts at a local midnight`() = runTest {
        assertEquals(
            today.minusDays((WIDGET_DAYS - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
            windowStart(clockNow, zone),
        )
    }

    @Test
    fun `the chronometer is handed the moment the session opened`() {
        // Chronometer counts in elapsedRealtime, which has no relation to wall time, so the widget
        // shows the wrong age unless the two are subtracted in the right order.
        assertEquals(910_000L, chronometerBase(openSince = 500_090_000L, now = 500_180_000L, elapsedRealtime = 1_000_000L))
        assertEquals(
            "a session stamped in the future counts from zero rather than backwards",
            1_000_000L,
            chronometerBase(openSince = 500_200_000L, now = 500_180_000L, elapsedRealtime = 1_000_000L),
        )
    }

    private suspend fun load() = loadWidgetData(container, zone)

    /** Generous, because it is waiting on Room's own executor rather than on anything here. */
    private suspend fun next(channel: Channel<WidgetData>): WidgetData =
        withTimeout(10_000) { channel.receive() }

    /** Local wall time on [today], as epoch millis. */
    private fun at(hourOfDay: Int, minute: Int): Long =
        today.atTime(hourOfDay, minute).atZone(zone).toInstant().toEpochMilli()

    /**
     * Writes through the DAOs rather than [it.eldavo.ylih.data.SessionRepository], for the same
     * reason DemoData does: the repository's whole job is to refuse backdated history.
     */
    private suspend fun seedPair(label: String, retiredAt: Long? = null): Long {
        val deviceId = container.database.deviceDao().insert(
            DeviceEntity(
                deviceKey = "bt:${label.hashCode()}",
                kind = DeviceKind.BLUETOOTH,
                defaultName = label,
                firstSeenAt = clockNow - 400 * day,
            ),
        )
        return container.database.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = label,
                generation = 1,
                startedAt = clockNow - 400 * day,
                retiredAt = retiredAt,
            ),
        )
    }

    private suspend fun seedSession(pairId: Long, from: Long, to: Long?, playingMs: Long? = null): Long =
        container.database.sessionDao().insert(
            SessionEntity(
                pairId = pairId,
                connectedAt = from,
                disconnectedAt = to,
                playingMs = playingMs,
                heartbeatAt = to ?: clockNow,
            ),
        )
}
