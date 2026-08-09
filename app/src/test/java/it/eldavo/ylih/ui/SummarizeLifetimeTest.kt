package it.eldavo.ylih.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import it.eldavo.ylih.data.DeviceEntity
import it.eldavo.ylih.data.DeviceKind
import it.eldavo.ylih.data.EndReason
import it.eldavo.ylih.data.PairEntity
import it.eldavo.ylih.data.SessionEntity
import it.eldavo.ylih.data.YlihDatabase
import it.eldavo.ylih.stats.Counting
import it.eldavo.ylih.stats.Stats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stats screen's lifetime figures are read off the per-pair aggregate now instead of off every
 * session ever recorded, and this is the test that says the swap changed nothing.
 *
 * `Stats.summarize` over a `List<Span>` is the definition — it is the older, simpler code, and it
 * is what the screen showed before. `summarizeLifetime` is an implementation of it in SQL plus a
 * little arithmetic, and the two are asserted equal, field for field, over the same database. The
 * point is not that either is right in isolation but that no lifetime total moved, which is the
 * one thing this app is not allowed to get wrong.
 *
 * Every awkward case the aggregate has to reproduce is seeded below: an open session and a closed
 * one, measured and unmeasured, playback that overruns the span it was measured in, a pair with no
 * sessions at all, and a retired pair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummarizeLifetimeTest {

    private lateinit var db: YlihDatabase

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            YlihDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun pair(key: String, retired: Boolean = false): Long {
        val deviceId = db.deviceDao().insert(
            DeviceEntity(
                deviceKey = key,
                kind = DeviceKind.BLUETOOTH,
                defaultName = key,
                firstSeenAt = now - 500 * hour,
            ),
        )
        return db.pairDao().insert(
            PairEntity(
                deviceId = deviceId,
                label = key,
                generation = 1,
                startedAt = now - 500 * hour,
                retiredAt = if (retired) now - hour else null,
            ),
        )
    }

    private suspend fun session(
        pairId: Long,
        from: Long,
        to: Long?,
        playingMs: Long? = null,
    ) = db.sessionDao().insert(
        SessionEntity(
            pairId = pairId,
            connectedAt = from,
            disconnectedAt = to,
            playingMs = playingMs,
            heartbeatAt = to ?: now,
            endReason = if (to != null) EndReason.DISCONNECTED else null,
        ),
    )

    /** Both routes to the same answer, which is the whole assertion. */
    private suspend fun assertAgree(what: String) {
        val summaries = db.pairDao().observeSummaries().first()
        val spans = db.sessionDao().getAll().map { it.toSpan() }
        for (counting in Counting.entries) {
            assertEquals(
                "$what, counting $counting",
                Stats.summarize(spans, now, counting),
                summaries.summarizeLifetime(now, counting),
            )
        }
    }

    @Test
    fun `no pairs at all`() = runTest {
        assertAgree("an install that has never connected anything")
    }

    @Test
    fun `a pair that has never recorded a session`() = runTest {
        pair("bt:AA:AA")
        assertAgree("a pair with no sessions")
    }

    @Test
    fun `closed sessions, none of them measured`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 10 * hour, now - 8 * hour)
        session(p, now - 5 * hour, now - 4 * hour)
        assertAgree("bluetooth-only history")
    }

    @Test
    fun `an open session counts its live tail`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 10 * hour, now - 8 * hour)
        session(p, now - 2 * hour, null)
        assertAgree("one still connected")
    }

    @Test
    fun `a measured open session is the one the playback figures have to reach`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 10 * hour, now - 8 * hour, playingMs = 30 * 60_000)
        session(p, now - 2 * hour, null, playingMs = 45 * 60_000)
        assertAgree("measuring while connected")
    }

    @Test
    fun `an unmeasured open session answers no playback question`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 10 * hour, now - 8 * hour, playingMs = 30 * 60_000)
        // Detailed tracking off for this one: it has connected time and nothing to say about
        // playback, so under PLAYBACK it must drop out of the count, the average and firstAt.
        session(p, now - 2 * hour, null)
        assertAgree("a mix of measured and not")
    }

    /**
     * The watcher banks playback in slices, so a clock step between two of them can credit more
     * than the span is long. `Stats.durationMs` clamps it; the SQL has to clamp it identically or
     * a lifetime playback total drifts above the connected time that contains it.
     */
    @Test
    fun `playback that overruns its own session is clamped the same way in both`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 10 * hour, now - 9 * hour, playingMs = 5 * hour)
        session(p, now - hour, null, playingMs = 40 * hour)
        assertAgree("playback longer than the span")
    }

    @Test
    fun `negative playback cannot subtract from a lifetime`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 10 * hour, now - 9 * hour, playingMs = -60_000)
        assertAgree("a nonsense figure from a clock that went backwards")
    }

    @Test
    fun `several pairs, retired and live, measured and not`() = runTest {
        val a = pair("bt:AA:AA")
        val b = pair("bt:BB:BB")
        val c = pair("bt:CC:CC", retired = true)
        pair("bt:DD:DD")

        session(a, now - 400 * hour, now - 399 * hour)
        session(a, now - 40 * hour, now - 38 * hour, playingMs = 90 * 60_000)
        session(a, now - 30 * 60_000, null, playingMs = 20 * 60_000)
        session(b, now - 100 * hour, now - 96 * hour, playingMs = 2 * hour)
        session(b, now - 3 * hour, now - 2 * hour)
        session(c, now - 300 * hour, now - 299 * hour, playingMs = 30 * 60_000)

        assertAgree("a realistic tree")
    }

    /** The figures being compared are not all zero, or the equality above proves nothing. */
    @Test
    fun `the fixture actually produces figures`() = runTest {
        val p = pair("bt:AA:AA")
        session(p, now - 40 * hour, now - 38 * hour, playingMs = 90 * 60_000)
        session(p, now - hour, null, playingMs = 30 * 60_000)

        val summary = db.pairDao().observeSummaries().first().summarizeLifetime(now, Counting.CONNECTED)

        assertEquals(2, summary.sessionCount)
        assertEquals(3 * hour, summary.totalMs)
        assertTrue("playback should have been measured", summary.hasPlaybackData)
        assertEquals(now - 40 * hour, summary.firstAt)
        assertEquals(now - hour, summary.openSince)
    }
}
