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
    fun durationMs(span: Span, now: Long): Long {
        val end = span.endAt ?: now
        return (end - span.startAt).coerceAtLeast(0)
    }

    fun totalMs(spans: List<Span>, now: Long): Long = spans.sumOf { durationMs(it, now) }

    fun summarize(spans: List<Span>, now: Long): Summary {
        if (spans.isEmpty()) {
            return Summary(0, 0, 0, 0, 0, 0, null, null, null)
        }
        val durations = spans.map { durationMs(it, now) }
        val total = durations.sum()
        return Summary(
            sessionCount = spans.size,
            totalMs = total,
            longestMs = durations.max(),
            averageMs = total / spans.size,
            playingMs = spans.sumOf { it.playingMs ?: 0L },
            measuredMs = spans.filter { it.playingMs != null }.sumOf { durationMs(it, now) },
            firstAt = spans.minOf { it.startAt },
            lastAt = spans.maxOf { it.endAt ?: now },
            openSince = spans.filter { it.endAt == null }.minOfOrNull { it.startAt },
        )
    }

    /**
     * Splits spans across local midnights so a session that runs overnight is credited to both
     * days. Uses [ZoneId] arithmetic rather than fixed 24 h blocks, so DST days (23 h / 25 h)
     * bucket correctly.
     */
    fun dailyMs(spans: List<Span>, zone: ZoneId, now: Long): Map<LocalDate, Long> {
        val out = mutableMapOf<LocalDate, Long>()
        for (span in spans) {
            var cursor = span.startAt
            val end = (span.endAt ?: now).coerceAtLeast(span.startAt)
            var day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            while (cursor < end) {
                val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val sliceEnd = minOf(end, nextMidnight)
                if (sliceEnd <= cursor) break // clock skew guard; never loop forever
                out[day] = (out[day] ?: 0L) + (sliceEnd - cursor)
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
    ): List<Pair<LocalDate, Long>> {
        val buckets = dailyMs(spans, zone, now)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = today.minusDays((days - 1).toLong())
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { it to (buckets[it] ?: 0L) }
            .toList()
    }

    /** Total over the last [days] local days, today included. */
    fun recentMs(spans: List<Span>, zone: ZoneId, now: Long, days: Int): Long =
        dailySeries(spans, zone, now, days).sumOf { it.second }

    /** Cost per listening hour, in the same minor units as [priceCents]. */
    fun costPerHour(priceCents: Long?, totalMs: Long): Double? {
        if (priceCents == null || totalMs <= 0) return null
        val hours = totalMs / 3_600_000.0
        if (hours <= 0) return null
        return priceCents / 100.0 / hours
    }
}
