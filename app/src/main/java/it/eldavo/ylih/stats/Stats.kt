package it.eldavo.ylih.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A connection span, decoupled from Room so the maths can be unit-tested on the JVM. */
data class Span(
    val startAt: Long,
    val endAt: Long?,
    val playingMs: Long?,
)

/**
 * What the totals are counting.
 *
 * [PLAYBACK] answers "how long did I actually listen", which only a session recorded while
 * detailed tracking was on can answer at all — `playingMs` is null for every other one, and those
 * are left out rather than counted as zero.
 */
enum class Counting { CONNECTED, PLAYBACK }

data class Summary(
    val sessionCount: Int,
    val totalMs: Long,
    val longestMs: Long,
    val averageMs: Long,
    val playingMs: Long,
    /** Connected time inside sessions where playback was actually measured. */
    val measuredMs: Long,
    val firstAt: Long?,
    val lastAt: Long?,
    val openSince: Long?,
) {
    val hasPlaybackData: Boolean get() = measuredMs > 0
}

object Stats {

    /** Length of a span; an open span runs up to [now]. */
    fun durationMs(span: Span, now: Long, counting: Counting = Counting.CONNECTED): Long {
        val end = span.endAt ?: now
        val connected = (end - span.startAt).coerceAtLeast(0)
        return when (counting) {
            Counting.CONNECTED -> connected
            // The watcher banks playback in slices as it goes, so a clock step between two of
            // them can credit more playback than the span is long. The span is the truth.
            Counting.PLAYBACK -> (span.playingMs ?: 0L).coerceIn(0L, connected)
        }
    }

    fun totalMs(spans: List<Span>, now: Long): Long = spans.sumOf { durationMs(it, now) }

    /**
     * Spans that can answer the question [counting] asks. A session recorded in Bluetooth-only
     * mode has no playback figure at all, and treating that as "zero minutes listened" would drag
     * down the average and move the first-seen date to something that never happened.
     */
    private fun counted(spans: List<Span>, counting: Counting): List<Span> = when (counting) {
        Counting.CONNECTED -> spans
        Counting.PLAYBACK -> spans.filter { it.playingMs != null }
    }

    fun summarize(
        spans: List<Span>,
        now: Long,
        counting: Counting = Counting.CONNECTED,
    ): Summary {
        val relevant = counted(spans, counting)
        if (relevant.isEmpty()) {
            return Summary(0, 0, 0, 0, 0, 0, null, null, null)
        }
        val durations = relevant.map { durationMs(it, now, counting) }
        val total = durations.sum()
        return Summary(
            sessionCount = relevant.size,
            totalMs = total,
            longestMs = durations.max(),
            averageMs = total / relevant.size,
            playingMs = relevant.sumOf { it.playingMs ?: 0L },
            measuredMs = relevant.filter { it.playingMs != null }.sumOf { durationMs(it, now) },
            firstAt = relevant.minOf { it.startAt },
            lastAt = relevant.maxOf { it.endAt ?: now },
            openSince = relevant.filter { it.endAt == null }.minOfOrNull { it.startAt },
        )
    }

    /**
     * Splits spans across local midnights so a session that runs overnight is credited to both
     * days. Uses [ZoneId] arithmetic rather than fixed 24 h blocks, so DST days (23 h / 25 h)
     * bucket correctly.
     */
    fun dailyMs(
        spans: List<Span>,
        zone: ZoneId,
        now: Long,
        counting: Counting = Counting.CONNECTED,
    ): Map<LocalDate, Long> {
        val out = mutableMapOf<LocalDate, Long>()
        for (span in counted(spans, counting)) {
            val connected = durationMs(span, now)
            val credit = durationMs(span, now, counting)
            if (connected <= 0 || credit <= 0) continue
            var cursor = span.startAt
            val end = (span.endAt ?: now).coerceAtLeast(span.startAt)
            var day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            while (cursor < end) {
                val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val sliceEnd = minOf(end, nextMidnight)
                if (sliceEnd <= cursor) break // clock skew guard; never loop forever
                // Only one playback total is stored per session, never *when* inside it the
                // audio ran, so an overnight session spreads its playback evenly over the days
                // it covers. Counting connected time, credit == connected and this is exact.
                out[day] = (out[day] ?: 0L) + (sliceEnd - cursor) * credit / connected
                cursor = sliceEnd
                day = day.plusDays(1)
            }
        }
        return out
    }

    /** Dense series for the charts: every day in the window, zero-filled, oldest first. */
    fun dailySeries(
        spans: List<Span>,
        zone: ZoneId,
        now: Long,
        days: Int,
        counting: Counting = Counting.CONNECTED,
    ): List<Pair<LocalDate, Long>> {
        val buckets = dailyMs(spans, zone, now, counting)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays((days - 1).toLong())
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { it to (buckets[it] ?: 0L) }
            .toList()
    }

    /** Total over the last [days] local days, today included. */
    fun recentMs(
        spans: List<Span>,
        zone: ZoneId,
        now: Long,
        days: Int,
        counting: Counting = Counting.CONNECTED,
    ): Long = dailySeries(spans, zone, now, days, counting).sumOf { it.second }

    /** Cost per listening hour, in the same minor units as [priceCents]. */
    fun costPerHour(priceCents: Long?, totalMs: Long): Double? {
        if (priceCents == null || totalMs <= 0) return null
        val hours = totalMs / 3_600_000.0
        if (hours <= 0) return null
        return priceCents / 100.0 / hours
    }
}
