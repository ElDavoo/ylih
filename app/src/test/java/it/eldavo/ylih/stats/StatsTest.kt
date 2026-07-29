package it.eldavo.ylih.stats

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, rome).toInstant().toEpochMilli()

    @Test
    fun `open session runs up to now`() {
        val start = at(2026, 3, 1, 10)
        val now = start + 90 * 60_000
        assertEquals(90 * 60_000L, Stats.durationMs(Span(start, null, null), now))
    }

    @Test
    fun `duration never goes negative when a clock jumps backwards`() {
        val start = at(2026, 3, 1, 10)
        assertEquals(0L, Stats.durationMs(Span(start, start - 5_000, null), start))
    }

    @Test
    fun `overnight session is split across local days`() {
        val start = at(2026, 3, 10, 22)
        val end = at(2026, 3, 11, 1)
        val buckets = Stats.dailyMs(listOf(Span(start, end, null)), rome, end)

        assertEquals(2 * 3_600_000L, buckets[LocalDate.of(2026, 3, 10)])
        assertEquals(1 * 3_600_000L, buckets[LocalDate.of(2026, 3, 11)])
    }

    @Test
    fun `spring forward day only has 23 hours`() {
        // Europe/Rome switches to DST on 2026-03-29; that local day is 23 h long.
        val start = at(2026, 3, 29, 0)
        val end = at(2026, 3, 30, 0)
        val buckets = Stats.dailyMs(listOf(Span(start, end, null)), rome, end)

        assertEquals(23 * 3_600_000L, buckets[LocalDate.of(2026, 3, 29)])
        assertNull(buckets[LocalDate.of(2026, 3, 30)])
    }

    @Test
    fun `autumn back day has 25 hours`() {
        val start = at(2026, 10, 25, 0)
        val end = at(2026, 10, 26, 0)
        val buckets = Stats.dailyMs(listOf(Span(start, end, null)), rome, end)

        assertEquals(25 * 3_600_000L, buckets[LocalDate.of(2026, 10, 25)])
    }

    @Test
    fun `daily series is dense and ends today`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(Span(at(2026, 5, 18, 9), at(2026, 5, 18, 11), null))
        val series = Stats.dailySeries(spans, rome, now, days = 7)

        assertEquals(7, series.size)
        assertEquals(LocalDate.of(2026, 5, 14), series.first().first)
        assertEquals(LocalDate.of(2026, 5, 20), series.last().first)
        assertEquals(2 * 3_600_000L, series.first { it.first == LocalDate.of(2026, 5, 18) }.second)
        assertEquals(0L, series.last().second)
    }

    @Test
    fun `recentMs only counts the window`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(
            Span(at(2026, 5, 19, 9), at(2026, 5, 19, 10), null),
            Span(at(2026, 4, 1, 9), at(2026, 4, 1, 15), null),
        )
        assertEquals(3_600_000L, Stats.recentMs(spans, rome, now, days = 7))
    }

    @Test
    fun `a one day window is today from local midnight, not the last 24 hours`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(
            // Overnight: only the three hours after midnight belong to today.
            Span(at(2026, 5, 19, 22), at(2026, 5, 20, 3), null),
            // Still connected: counted up to now.
            Span(at(2026, 5, 20, 11), null, null),
        )
        assertEquals(4 * 3_600_000L, Stats.recentMs(spans, rome, now, days = 1))
    }

    @Test
    fun `summary separates measured playback from unmeasured sessions`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(
            // Measured: two hours connected, one hour playing.
            Span(at(2026, 5, 20, 8), at(2026, 5, 20, 10), 3_600_000L),
            // Not measured (Bluetooth-only mode): must not drag the playback share down.
            Span(at(2026, 5, 19, 8), at(2026, 5, 19, 12), null),
        )
        val summary = Stats.summarize(spans, now)

        assertEquals(2, summary.sessionCount)
        assertEquals(6 * 3_600_000L, summary.totalMs)
        assertEquals(4 * 3_600_000L, summary.longestMs)
        assertEquals(3 * 3_600_000L, summary.averageMs)
        assertEquals(3_600_000L, summary.playingMs)
        assertEquals(2 * 3_600_000L, summary.measuredMs)
        assertNull(summary.openSince)
    }

    @Test
    fun `counting playback leaves out the sessions that never measured it`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(
            Span(at(2026, 5, 20, 8), at(2026, 5, 20, 10), 3_600_000L),
            // Recorded in Bluetooth-only mode: zero listening is not something it observed, so
            // counting it as zero would halve the average and backdate the first-seen day.
            Span(at(2026, 5, 19, 8), at(2026, 5, 19, 12), null),
        )
        val summary = Stats.summarize(spans, now, Counting.PLAYBACK)

        assertEquals(1, summary.sessionCount)
        assertEquals(3_600_000L, summary.totalMs)
        assertEquals(3_600_000L, summary.longestMs)
        assertEquals(3_600_000L, summary.averageMs)
        assertEquals(at(2026, 5, 20, 8), summary.firstAt)
    }

    @Test
    fun `playback with nothing ever measured is empty, not zero sessions of history`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(Span(at(2026, 5, 19, 8), at(2026, 5, 19, 12), null))
        val summary = Stats.summarize(spans, now, Counting.PLAYBACK)

        assertEquals(0, summary.sessionCount)
        assertEquals(0L, summary.totalMs)
        assertNull(summary.firstAt)
    }

    @Test
    fun `playback can never exceed the session that recorded it`() {
        // A clock step between two of the watcher's slices can bank more than really elapsed.
        val start = at(2026, 5, 20, 8)
        val end = start + 3_600_000L
        val span = Span(start, end, 5 * 3_600_000L)
        assertEquals(3_600_000L, Stats.durationMs(span, end, Counting.PLAYBACK))
    }

    @Test
    fun `an overnight session spreads its playback over the days it covers`() {
        // Only a per-session total is stored, never when inside it the audio ran, so the split is
        // proportional: two of the three connected hours fall on the 10th.
        val start = at(2026, 3, 10, 22)
        val end = at(2026, 3, 11, 1)
        val buckets = Stats.dailyMs(
            listOf(Span(start, end, 90 * 60_000L)),
            rome,
            end,
            Counting.PLAYBACK,
        )

        assertEquals(60 * 60_000L, buckets[LocalDate.of(2026, 3, 10)])
        assertEquals(30 * 60_000L, buckets[LocalDate.of(2026, 3, 11)])
    }

    @Test
    fun `a session that never ran contributes no day at all`() {
        // A connect and a disconnect in the same millisecond — the bar chart must not grow a
        // zero-height day for it, and the proportional split below would divide by zero.
        val start = at(2026, 5, 20, 8)
        assertEquals(emptyMap<LocalDate, Long>(), Stats.dailyMs(listOf(Span(start, start, null)), rome, start))
    }

    @Test
    fun `a measured session with nothing played contributes no day either`() {
        val start = at(2026, 5, 20, 8)
        val end = start + 2 * 3_600_000L
        assertEquals(
            emptyMap<LocalDate, Long>(),
            Stats.dailyMs(listOf(Span(start, end, 0L)), rome, end, Counting.PLAYBACK),
        )
    }

    @Test
    fun `an unmeasured span asked for playback answers zero rather than its length`() {
        // Bluetooth-only sessions have no playback figure. `counted` keeps them out of the
        // aggregates, but the per-row duration is reachable on its own.
        val start = at(2026, 5, 20, 8)
        val end = start + 3_600_000L
        assertEquals(0L, Stats.durationMs(Span(start, end, null), end, Counting.PLAYBACK))
    }

    @Test
    fun `a recent window counting playback ignores unmeasured sessions entirely`() {
        val now = at(2026, 5, 20, 12)
        val spans = listOf(
            Span(at(2026, 5, 19, 9), at(2026, 5, 19, 11), 1_800_000L),
            Span(at(2026, 5, 18, 9), at(2026, 5, 18, 15), null),
        )
        assertEquals(
            1_800_000L,
            Stats.recentMs(spans, rome, now, days = 7, counting = Counting.PLAYBACK),
        )
    }

    @Test
    fun `summary of nothing is all zeroes`() {
        val summary = Stats.summarize(emptyList(), at(2026, 5, 20, 12))
        assertEquals(0, summary.sessionCount)
        assertEquals(0L, summary.totalMs)
        assertNull(summary.firstAt)
    }

    @Test
    fun `cost per hour needs both a price and some hours`() {
        // 100.00 spread over 200 h.
        assertEquals(0.5, Stats.costPerHour(10_000, 200 * 3_600_000L)!!, 1e-9)
        assertNull(Stats.costPerHour(null, 3_600_000L))
        assertNull(Stats.costPerHour(10_000, 0))
    }
}
