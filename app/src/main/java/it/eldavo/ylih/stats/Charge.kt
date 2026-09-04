package it.eldavo.ylih.stats

/** One battery level a headset reported, and the session it was reported inside. */
data class Reading(val sessionId: Long, val at: Long, val level: Int)

/**
 * A hundred percentage points of drain, and the listening they bought.
 *
 * A charge cycle here is *a hundred points used*, not a discharge from full: five 100 → 80 evenings
 * are one cycle, which is both how battery wear is actually counted and the definition the feature
 * was asked for with.
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
     * Time one full cycle buys, over every point observed rather than over the completed cycles
     * alone. A pair one and four fifths of the way through its second cycle would otherwise report
     * from its first one for months, and the whole point of the figure is that it moves.
     */
    val msPerCycle: Long
        get() = if (pointsDrained <= 0) 0L else countedMs * POINTS_PER_CYCLE / pointsDrained

    val cyclesFraction: Double get() = pointsDrained.toDouble() / POINTS_PER_CYCLE
}

/** A hundred points used. */
const val POINTS_PER_CYCLE = 100

/**
 * Charge cycles out of battery readings — pure functions over [Reading] and [Span], decoupled from
 * Room the way [Stats] is, so the arithmetic runs as a plain JVM test.
 *
 * The rule the whole file rests on: **a drop counts only between two readings taken inside one
 * session.** Then the points drained and the listening credited cover exactly the same stretches of
 * time, and the ratio between them means something. Counting a drop across a gap would break that
 * twice over — there is no listening to attach to it, and a headset charged partway through the gap
 * reports a level that makes the drain look smaller than it was, with nothing to say so.
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
        // The cycle being filled: null start until the first points land in it, so a cycle begins
        // where drain begins rather than where the previous one happened to end.
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

            // A segment can be a hundred points on its own — a headset that reports once at 100 and
            // next at 0 — so this fills a bucket rather than assuming it only tops one up. It can
            // never run more than twice: a hundred points is the most one segment can carry.
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
     * Readings are grouped by session before being paired up, which is what enforces the rule at
     * the top of this file: the last reading of one session and the first of the next are never
     * adjacent. A level going *up* is a charge — it ends one run and starts another, and carries no
     * drain of its own.
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
            // Under PLAYBACK a session that never measured playback cannot answer the question at
            // all, so its drain leaves both sides of the ratio — the same choice `Stats.counted`
            // makes, and for the same reason: crediting it zero minutes would read as a battery
            // that gave nothing back.
            val rate = playbackRate(span, now, counting) ?: continue
            val sorted = group.sortedBy { it.at }
            for (i in 1 until sorted.size) {
                val from = sorted[i - 1]
                val to = sorted[i]
                val points = from.level - to.level
                if (points <= 0) continue
                val elapsed = to.at - from.at
                // A backwards step is a corrected clock rather than a stretch of listening; the
                // drain is real but nothing can be credited to it, so neither side counts it.
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
     * How much counted time a millisecond of a session is worth, as numerator over denominator so
     * the arithmetic stays in Long.
     *
     * Playback is stored as one total per session and never as *when* inside it the audio ran, so a
     * stretch of the session gets its share of that total — the same even spread [Stats.dailyMs]
     * makes when an overnight session is split across midnight. Null means this session cannot
     * answer, and its drain is dropped.
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
