package it.eldavo.ylih.stats

/** One battery level a headset reported, and the session it was reported inside. */
data class Reading(val sessionId: Long, val at: Long, val level: Int)

/**
 * A hundred percentage points of drain, and the listening they bought.
 *
 * A cycle is *a hundred points used*, not a discharge from full: five 100 → 80 evenings are one
 * cycle — how battery wear is counted, and the definition the feature asked for.
 */
data class Cycle(val startAt: Long, val endAt: Long, val countedMs: Long)

/**
 * What a pair's readings add up to. [cycles] are the completed ones, oldest first — the series
 * whose shape answers "is this battery getting worse".
 */
data class ChargeSummary(
    val cycles: List<Cycle>,
    /** Every point of drain observed, the part-finished cycle at the end included. */
    val pointsDrained: Int,
    val countedMs: Long,
    /** Points into the cycle currently in progress; `0` when the last one landed exactly. */
    val partialPoints: Int,
) {
    val hasData: Boolean get() = pointsDrained > 0

    /**
     * Time one full cycle buys, over every point observed, not just completed cycles — else a pair
     * 1.8 cycles in would report its first cycle for months.
     */
    val msPerCycle: Long
        get() = if (pointsDrained <= 0) 0L else countedMs * POINTS_PER_CYCLE / pointsDrained

    val cyclesFraction: Double get() = pointsDrained.toDouble() / POINTS_PER_CYCLE

    /**
     * What a charge buys now against what it bought when the pair was new, as a fraction — 0.53
     * meaning "a bit over half of what it managed then". Null until two cycles complete, the same
     * point the chart appears.
     *
     * Not literal last-over-first: a cycle is one fortnight of however the headphones were used,
     * and on coarse readings — a headset reporting in 25-point steps gives four segments per
     * discharge — one unlucky pair of cycles can move this further than a year of real wear. Both
     * ends are averaged over [comparisonWindow] instead, the same arithmetic with the noise
     * removed, reducing to "last against first" when that's all there is.
     */
    val versusNew: Double?
        get() {
            if (cycles.size < 2) return null
            val window = comparisonWindow
            val first = cycles.take(window).sumOf { it.countedMs } / window
            val last = cycles.takeLast(window).sumOf { it.countedMs } / window
            return if (first <= 0L) null else last.toDouble() / first
        }

    /**
     * Cycles averaged at each end for [versusNew]: a quarter of them, capped at five.
     *
     * A quarter rather than a fixed number so a young pair isn't asked for history it lacks — at
     * four cycles this is 1, a plain comparison. The two ends never overlap, since twice a quarter
     * is half.
     */
    val comparisonWindow: Int
        get() = (cycles.size / 4).coerceIn(1, MAX_COMPARISON_CYCLES)
}

/** The most cycles [ChargeSummary.versusNew] averages at each end. */
const val MAX_COMPARISON_CYCLES = 5

/** A hundred points used. */
const val POINTS_PER_CYCLE = 100

/**
 * Charge cycles out of battery readings — pure functions over [Reading] and [Span], decoupled from
 * Room like [Stats], so this runs as a plain JVM test.
 *
 * The file's rule: **a drop counts only between two readings taken inside one session.** Then the
 * points drained and the listening credited cover the same stretch of time, so their ratio means
 * something. Counting a drop across a gap breaks that twice: there's no listening to attach it to,
 * and a headset charged partway through the gap reports a level that makes the drain look smaller
 * than it was, with nothing to say so.
 */
object Charge {

    /** One stretch between two readings over which the battery fell. */
    private data class Segment(
        val startAt: Long,
        val endAt: Long,
        val points: Int,
        val creditMs: Long,
    )

    /**
     * @param spans the session behind each reading, needed only to apportion playback.
     * @param now for the open session's length; every closed one carries its own end.
     */
    fun summarize(
        readings: List<Reading>,
        spans: Map<Long, Span>,
        now: Long,
        counting: Counting = Counting.CONNECTED,
    ): ChargeSummary {
        val segments = segments(readings, spans, now, counting)

        val cycles = mutableListOf<Cycle>()
        var totalPoints = 0
        var totalMs = 0L
        // The cycle being filled: null start until the first points land, so a cycle begins where
        // drain begins, not where the previous one ended.
        var bucketStart: Long? = null
        var bucketPoints = 0
        var bucketMs = 0L

        for (segment in segments) {
            totalPoints += segment.points
            totalMs += segment.creditMs

            var cursorAt = segment.startAt
            var remainingPoints = segment.points
            var remainingMs = segment.creditMs
            var remainingSpan = segment.endAt - segment.startAt

            // A segment can be a hundred points on its own (a headset reporting 100 then 0), so
            // this fills a bucket instead of assuming it only tops one up. Runs at most twice,
            // since a hundred points is the most one segment can carry.
            while (bucketPoints + remainingPoints >= POINTS_PER_CYCLE) {
                val take = POINTS_PER_CYCLE - bucketPoints
                val takeMs = remainingMs * take / remainingPoints
                val takeSpan = remainingSpan * take / remainingPoints
                val boundaryAt = cursorAt + takeSpan
                cycles += Cycle(
                    startAt = bucketStart ?: cursorAt,
                    endAt = boundaryAt,
                    countedMs = bucketMs + takeMs,
                )
                remainingPoints -= take
                remainingMs -= takeMs
                remainingSpan -= takeSpan
                cursorAt = boundaryAt
                bucketStart = null
                bucketPoints = 0
                bucketMs = 0L
            }

            if (remainingPoints > 0) {
                if (bucketStart == null) bucketStart = cursorAt
                bucketPoints += remainingPoints
                bucketMs += remainingMs
            }
        }

        return ChargeSummary(
            cycles = cycles,
            pointsDrained = totalPoints,
            countedMs = totalMs,
            partialPoints = bucketPoints,
        )
    }

    /**
     * The drops, oldest first.
     *
     * Readings are grouped by session before pairing, enforcing the file's rule: the last reading of
     * one session and the first of the next are never adjacent. A level going *up* is a charge — it
     * ends one run and starts another, with no drain of its own.
     */
    private fun segments(
        readings: List<Reading>,
        spans: Map<Long, Span>,
        now: Long,
        counting: Counting,
    ): List<Segment> {
        val out = mutableListOf<Segment>()
        for ((sessionId, group) in readings.groupBy { it.sessionId }) {
            val span = spans[sessionId]
            // Under PLAYBACK a session that never measured playback can't answer, so its drain
            // drops from both sides of the ratio — `Stats.counted` makes the same choice: crediting
            // it zero minutes would read as a battery giving nothing back.
            val rate = playbackRate(span, now, counting) ?: continue
            val sorted = group.sortedBy { it.at }
            for (i in 1 until sorted.size) {
                val from = sorted[i - 1]
                val to = sorted[i]
                val points = from.level - to.level
                if (points <= 0) continue
                val elapsed = to.at - from.at
                // A backwards step is a corrected clock, not listening; the drain is real but
                // nothing credits to it, so neither side counts it.
                if (elapsed <= 0) continue
                out += Segment(
                    startAt = from.at,
                    endAt = to.at,
                    points = points,
                    creditMs = (elapsed * rate.first / rate.second).coerceAtLeast(0L),
                )
            }
        }
        return out.sortedBy { it.startAt }
    }

    /**
     * How much counted time a millisecond of session is worth, as numerator over denominator so the
     * arithmetic stays in Long.
     *
     * Playback is stored as one total per session, never *when* the audio ran, so a stretch of the
     * session gets its share of that total — the same even spread [Stats.dailyMs] uses splitting an
     * overnight session across midnight. Null means the session can't answer, so its drain is
     * dropped.
     */
    private fun playbackRate(span: Span?, now: Long, counting: Counting): Pair<Long, Long>? =
        when (counting) {
            Counting.CONNECTED -> 1L to 1L
            Counting.PLAYBACK -> {
                if (span?.playingMs == null) {
                    null
                } else {
                    val connected = Stats.durationMs(span, now)
                    val playing = Stats.durationMs(span, now, Counting.PLAYBACK)
                    if (connected <= 0) null else playing to connected
                }
            }
        }
}
